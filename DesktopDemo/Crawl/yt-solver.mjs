// Source for Crawl/yt-solver.cjs. The app runs the bundled script with Node.js.
import { readFileSync } from "node:fs";
import { parse } from "meriyah";
import { generate } from "astring";

function matchesStructure(obj, structure) {
  if (Array.isArray(structure)) {
    if (!Array.isArray(obj)) return false;
    return structure.length === obj.length && structure.every((value, index) => matchesStructure(obj[index], value));
  }
  if (typeof structure === "object" && structure) {
    if (!obj) return !structure;
    if ("or" in structure) return structure.or.some((node) => matchesStructure(obj, node));
    if ("anykey" in structure && Array.isArray(structure.anykey)) {
      const haystack = Array.isArray(obj) ? obj : Object.values(obj);
      return structure.anykey.every((value) => haystack.some((el) => matchesStructure(el, value)));
    }
    for (const [key, value] of Object.entries(structure)) {
      if (!matchesStructure(obj[key], value)) return false;
    }
    return true;
  }
  return structure === obj;
}

const identifier = {
  or: [
    {
      type: "ExpressionStatement",
      expression: {
        type: "AssignmentExpression",
        operator: "=",
        left: { or: [{ type: "Identifier" }, { type: "MemberExpression" }] },
        right: { type: "FunctionExpression", async: false },
      },
    },
    { type: "FunctionDeclaration", async: false, id: { type: "Identifier" } },
    {
      type: "VariableDeclaration",
      declarations: { anykey: [{ type: "VariableDeclarator", init: { type: "FunctionExpression", async: false } }] },
    },
  ],
};

const marker = {
  type: "ExpressionStatement",
  expression: {
    type: "CallExpression",
    callee: { type: "MemberExpression", object: { type: "Identifier" }, property: {}, optional: false },
    arguments: [{ type: "Literal", value: "alr" }, { type: "Literal", value: "yes" }],
    optional: false,
  },
};

function arrow(data) {
  return parse(data).body[0].expression;
}

function extract(node) {
  if (!matchesStructure(node, identifier)) return null;
  const options = [];
  if (node.type === "FunctionDeclaration") {
    if (node.id && node.body?.body) options.push({ name: node.id, statements: node.body.body });
  } else if (node.type === "ExpressionStatement") {
    if (node.expression.type !== "AssignmentExpression") return null;
    const body = node.expression.right?.body?.body;
    if (node.expression.left && body) options.push({ name: node.expression.left, statements: body });
  } else if (node.type === "VariableDeclaration") {
    for (const declaration of node.declarations) {
      const body = declaration.init?.body?.body;
      if (declaration.id && body) options.push({ name: declaration.id, statements: body });
    }
  }
  for (const option of options) {
    if (matchesStructure(option.statements, { anykey: [marker] })) return createSolver(option.name);
  }
  return null;
}

function createSolver(expression) {
  return arrow(`
({sig, n}) => {
  const url = (${generate(expression)})("https://youtube.com/watch?v=yt-dlp-wins", "s", sig ? encodeURIComponent(sig) : undefined);
  url.set("n", n);
  const proto = Object.getPrototypeOf(url);
  const keys = Object.keys(proto).concat(Object.getOwnPropertyNames(proto));
  for (const key of keys) {
    if (!["constructor", "set", "get", "clone"].includes(key)) {
      url[key]();
      break;
    }
  }
  const s = url.get("s");
  return { sig: s ? decodeURIComponent(s) : null, n: url.get("n") ?? null };
}
`);
}

function modifyPlayer(program) {
  const body = program.body;
  let block;
  if (body.length === 1) {
    const func = body[0];
    if (func?.type === "ExpressionStatement" && func.expression.type === "CallExpression" && func.expression.callee.type === "MemberExpression" && func.expression.callee.object.type === "FunctionExpression") {
      block = func.expression.callee.object.body;
    }
  } else if (body.length === 2) {
    const func = body[1];
    if (func?.type === "ExpressionStatement" && func.expression.type === "CallExpression" && func.expression.callee.type === "FunctionExpression") {
      block = func.expression.callee.body;
      block.body.splice(0, 1);
    }
  }
  if (!block) throw new Error("播放器脚本结构变了");
  block.body = block.body.filter((node) => {
    if (node.type === "ExpressionStatement") {
      if (node.expression.type === "AssignmentExpression") return true;
      return node.expression.type === "Literal";
    }
    return true;
  });
  return block.body;
}

function multiTry(generators) {
  return arrow(`
(_input) => {
  const _results = new Set();
  const errors = [];
  for (const _generator of ${generate({ type: "ArrayExpression", elements: generators })}) {
    try { _results.add(_generator(_input)); }
    catch (e) { errors.push(e); }
  }
  if (!_results.size) throw \`no solutions: \${errors.join(", ")}\`;
  if (_results.size !== 1) throw \`invalid solutions: \${[..._results].map((x) => JSON.stringify(x)).join(", ")}\`;
  return _results.values().next().value;
}
`);
}

function preprocess(data) {
  const program = parse(data);
  const statements = modifyPlayer(program);
  const found = { n: [], sig: [] };
  for (const statement of statements) {
    const result = extract(statement);
    if (!result) continue;
    found.n.push(makeField(result, "n"));
    found.sig.push(makeField(result, "sig"));
  }
  if (found.n.length === 0) throw new Error("没有找到签名函数");
  for (const name of ["n", "sig"]) {
    statements.push({
      type: "ExpressionStatement",
      expression: {
        type: "AssignmentExpression",
        operator: "=",
        left: { type: "MemberExpression", computed: false, optional: false, object: { type: "Identifier", name: "_result" }, property: { type: "Identifier", name } },
        right: multiTry(found[name]),
      },
    });
  }
  const setup = parse(`
if (typeof globalThis.XMLHttpRequest === "undefined") globalThis.XMLHttpRequest = { prototype: {} };
globalThis.location = new URL("https://www.youtube.com/watch?v=yt-dlp-wins");
if (typeof globalThis.document === "undefined") globalThis.document = Object.create(null);
if (typeof globalThis.navigator === "undefined") globalThis.navigator = Object.create(null);
if (typeof globalThis.self === "undefined") globalThis.self = globalThis;
if (typeof globalThis.window === "undefined") globalThis.window = globalThis;
`).body;
  program.body.splice(0, 0, ...setup);
  return generate(program);
}

function makeField(result, name) {
  return {
    type: "ArrowFunctionExpression",
    params: [{ type: "Identifier", name }],
    async: false,
    expression: true,
    generator: false,
    body: {
      type: "MemberExpression",
      computed: false,
      optional: false,
      object: {
        type: "CallExpression",
        optional: false,
        callee: result,
        arguments: [{
          type: "ObjectExpression",
          properties: [{ type: "Property", key: { type: "Identifier", name }, value: { type: "Identifier", name }, kind: "init", computed: false, method: false, shorthand: true }],
        }],
      },
      property: { type: "Identifier", name },
    },
  };
}

const input = JSON.parse(readFileSync(0, "utf8"));
const code = preprocess(input.player);
const resultObj = { n: null, sig: null };
Function("_result", code)(resultObj);
const responses = input.requests.map((request) => {
  const solver = resultObj[request.type];
  if (!solver) return { type: "error", error: "没有找到 " + request.type };
  try {
    const data = {};
    for (const challenge of request.challenges) data[challenge] = solver(challenge);
    return { type: "result", data };
  } catch (error) {
    return { type: "error", error: error instanceof Error ? error.message : String(error) };
  }
});
process.stdout.write(JSON.stringify({ type: "result", responses }));
