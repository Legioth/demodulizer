# Demodulizer proof of concept

Proof of concept that shows how npm modules can be converted on the fly to HTML imports in Flow. This is thus the opposite of what is done by Polymer's modulizer. Please note that this approach does not prevent `customElements` conflicts that would happen if using both Polymer 2 and Polymer 3 on the same page. On the other hand, it enables using LitElement components in with Vaadin versions based on Polymer 2.

Some limitations:
- Only supports the `import` and `export` forms used through the used example component.
- There's no special production build support. This means that even in production, there would be tens of small files loaded. Furthermore, the scripts are not transpiled to work in older browsers.
- Dynamic imports are not supported
- Does not support some special features of the module format such as circular dependencies or `import.meta`.

This works by using npm webjars to transitively resolve all needed frontend dependencies and then there is a servlet filter that intercepts requests for `frontend/npm_components/*`, finds the corresponding file from a webjar and rewrites it into an HTML import. The exports and imports of the JS module are rewritten using Closure to store all values `window.Vaadin`.

In this way, an export like
```
export const foo = "bar"; 
```
is rewritten to
```
const foo = "bar";
window.Vaadin.modules["my-module/foo.js"].foo = foo;
```
and an import like 
```
import { foo as bar} from "./foo.js";
```
becomes a corresponding HTML import and a pseudo import
```
<link rel=import href="./foo.html">
<script>
  const bar = window.Vaadin.modules["my-module/foo.js"].foo
</script>
```
