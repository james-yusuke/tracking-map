/* eslint-disable @typescript-eslint/no-require-imports */
const Module = require("node:module");

const typescript6 = require("typescript-eslint-typescript");
const originalLoad = Module._load;

Module._load = function loadWithTypeScript6(request, parent, isMain) {
  if (request === "typescript") {
    return typescript6;
  }

  return originalLoad.call(this, request, parent, isMain);
};
