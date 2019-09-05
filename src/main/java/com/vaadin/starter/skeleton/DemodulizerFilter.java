package com.vaadin.starter.skeleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.javascript.jscomp.CodePrinter;
import com.google.javascript.jscomp.CodePrinter.Builder;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.JsAst;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

@WebFilter("/frontend/npm_components/*")
public class DemodulizerFilter implements Filter {
    private static final ClassLoader classLoader = DemodulizerFilter.class.getClassLoader();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void destroy() {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            if (handleRequest(httpRequest, (HttpServletResponse) response)) {
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static boolean handleRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();

        Matcher matcher = Pattern.compile("/frontend/npm_components/([^/]+)/(.*)\\.html").matcher(pathInfo);
        if (!matcher.matches()) {
            return false;
        }

        String moduleName = matcher.group(1);
        String jsFileName = matcher.group(2) + ".js";

        String qualifiedName = moduleName + "/" + jsFileName;

        String versionNumber = findVersionNumber(moduleName);

        if (versionNumber == null) {
            return false;
        }

        try (InputStream contents = classLoader.getResourceAsStream(
                "/META-INF/resources/webjars/" + moduleName + "/" + versionNumber + "/" + jsFileName);
                PrintWriter writer = response.getWriter()) {

            Compiler compiler = new Compiler();
            CompilerOptions options = new CompilerOptions();
            options.setIdeMode(true);
            compiler.initOptions(options);

            JsAst jsAst = new JsAst(SourceFile.fromInputStream(jsFileName, contents, StandardCharsets.UTF_8));
            Node astRoot = jsAst.getAstRoot(compiler);
            NodeTraversal.traverse(compiler, astRoot, new NodeTraversal.AbstractPostOrderCallback() {
                @Override
                public void visit(NodeTraversal t, Node n, Node parent) {
                    if (n.isImport()) {
                        Node nameNode = n.getFirstChild();
                        Node specsNode = n.getSecondChild();
                        String from = n.getChildAtIndex(2).getString();

                        String moduleKey = getModuleKey(from);

                        String relativeImportUrl = getRelativeImportUrl(from);

                        writer.append("<link rel=import href='").append(relativeImportUrl).append("'>\n");

                        if (nameNode.isName()) {
                            // import foo from 'bar'

                            if (!specsNode.isEmpty()) {
                                throw new IllegalStateException(
                                        "Don't know how to deal with both name and spec at the same time");
                            }

                            // const foo = defaultExport[module]
                            n.replaceWith(IR.constNode(nameNode.cloneNode(), getDefaultNode(moduleKey)));
                        } else if (specsNode.isImportSpecs()) {
                            // import { foo as a, b } from 'bar'

                            // TODO traverse as a linked list
                            for (int i = 0; i < specsNode.getChildCount(); i++) {
                                Node specNode = specsNode.getChildAtIndex(i);
                                Node importName = specNode.getFirstChild();
                                Node importAs = specNode.getSecondChild();

                                // const a = exports[module].bar
                                Node importConst = IR.constNode(importAs.cloneNode(),
                                        getModuleExportNode(moduleKey, importName));

                                n.getParent().addChildAfter(importConst, n);
                            }
                            n.detach();
                        } else {
                            throw new IllegalStateException("Don't know how to deal with import " + n);
                        }
                    } else if (n.isExport()) {
                        Node exported = n.getFirstChild();
                        if (exported.isConst() || exported.isClass() || exported.isFunction()) {
                            Node exportName = exported.getFirstChild();

                            // export const foo = ... -> const foo = ...
                            exported.detach();
                            n.replaceWith(exported);

                            // exports[module].foo = foo;
                            Node newExport = createExport(n, exportName);
                            exported.getParent().addChildAfter(newExport, exported);
                        } else if (exported.isName()) {
                            // export default foo -> moduleDefaults[module] =
                            // foo
                            n.replaceWith(createExport(n, exported));
                        } else if (exported.isExportSpecs()) {
                            // Export {foo as bar,...} from <second child>

                            String from = n.getSecondChild().getString();
                            String importedModuleKey = getModuleKey(from);
                            String relativeImportUrl = getRelativeImportUrl(from);

                            // This might cause duplicates, but that *should* be
                            // handled by the browser
                            writer.append("<link rel=import href='").append(relativeImportUrl).append("'>\n");

                            for (int i = 0; i < exported.getChildCount(); i++) {
                                Node exportSpec = exported.getChildAtIndex(i);

                                String importName = exportSpec.getFirstChild().getString();
                                String exportName = exportSpec.getSecondChild().getString();

                                // exports[thisModule].foo =
                                // exports[thateModule].bar
                                Node assign = IR.assign(getModuleExportNode(qualifiedName, IR.string(exportName)),
                                        getModuleExportNode(importedModuleKey, IR.name(importName)));
                                n.getParent().addChildAfter(IR.exprResult(assign), n);
                            }

                            n.detach();
                        } else if (exported.isEmpty() && n.getBooleanProp(Node.EXPORT_ALL_FROM)) {
                            // export * from <second child>

                            String from = n.getSecondChild().getString();
                            String importedModuleKey = getModuleKey(from);
                            String relativeImportUrl = getRelativeImportUrl(from);

                            writer.append("<link rel=import href='").append(relativeImportUrl).append("'>\n");

                            // exports = {...exports, ...from}
                            Node newExport = IR.assign(getModuleExportsNode(qualifiedName),
                                    IR.objectlit(IR.objectSpread(getModuleExportsNode(qualifiedName)),
                                            IR.objectSpread(getModuleExportsNode(importedModuleKey))));

                            n.replaceWith(IR.exprResult(newExport));
                        } else {
                            throw new IllegalStateException("Cannot export " + n.toStringTree());
                        }
                    }
                }

                private String getRelativeImportUrl(String from) {
                    if (from.startsWith(".")) {
                        if (from.endsWith(".js")) {
                            return from.replaceFirst("js$", "html");
                        } else {
                            throw new IllegalStateException("Weird non-js import: " + from);
                        }
                    } else {
                        // Bare import
                        String resolvedFrom = resolveBareImport(from);

                        int depth = (int) qualifiedName.chars().filter(c -> c == '/').count();
                        String relativization = new String(new char[depth]).replace("\0", "../");

                        return relativization + resolvedFrom.replaceFirst("js$", "html");
                    }
                }

                private String getModuleKey(String from) {
                    if (from.startsWith(".")) {
                        if (from.endsWith(".js")) {
                            return normalize(qualifiedName, from);
                        } else {
                            throw new IllegalStateException("Weird non-js import: " + from);
                        }
                    } else {
                        return resolveBareImport(from);
                    }
                }

                private String resolveBareImport(String from) {
                    // TODO resolve properly based on "main" property in
                    // package.json
                    if (!from.endsWith(".js")) {
                        return from + "/" + from + ".js";
                    } else {
                        return from;
                    }
                }

                private String normalize(String base, String rest) {
                    return Paths.get(base).getParent().resolve(rest).normalize().toString();
                }

                private Node getModuleExportNode(String moduleKey, Node importName) {
                    return IR.getprop(getModuleExportsNode(moduleKey), IR.string(importName.getString()));
                }

                private Node getModuleExportsNode(String moduleKey) {
                    Node vaadinModules = IR.getprop(IR.name("window"), "Vaadin", "modules");

                    return IR.getelem(vaadinModules, IR.string(moduleKey));
                }

                private Node getDefaultNode(String moduleKey) {
                    Node vaadinDefaults = IR.getprop(IR.name("window"), "Vaadin", "moduleDefaults");

                    return IR.getelem(vaadinDefaults, IR.string(moduleKey));
                }

                private Node createExport(Node exportNode, Node exportName) {
                    Node exportTarget;

                    if (exportNode.getBooleanProp(Node.EXPORT_DEFAULT)) {
                        exportTarget = getDefaultNode(qualifiedName);
                    } else {
                        exportTarget = getModuleExportNode(qualifiedName, exportName);
                    }
                    return IR.exprResult(IR.assign(exportTarget, exportName.cloneNode()));
                }
            });

            Builder printBuilder = new CodePrinter.Builder(astRoot);
            printBuilder.setPrettyPrint(true);
            String js = printBuilder.build();

            response.setContentType("text/html");

            writer.append("<script>\n");
            writer.append("window.Vaadin.modules = window.Vaadin.modules || {};\n");
            writer.append("window.Vaadin.moduleDefaults = window.Vaadin.moduleDefaults || {};\n");
            writer.append("window.Vaadin.modules['").append(qualifiedName).append("']={};\n");
            writer.append("(function() {\n");
            writer.append(js);
            writer.append("})();\n");
            writer.append("\n</script>");

            return true;
        }
    }

    private static String findVersionNumber(String moduleName) throws IOException {
        try (InputStream pomPropertiesStream = classLoader
                .getResourceAsStream("/META-INF/maven/org.webjars.npm/" + moduleName + "/pom.properties")) {
            if (pomPropertiesStream == null) {
                return null;
            }

            Properties pomProperties = new Properties();
            pomProperties.load(pomPropertiesStream);
            return pomProperties.getProperty("version");
        }
    }
}
