package com.aliyun.odps.agentic.skill.manifest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
/** Directory and JAR discovery for plain Java; hosts may supply their own resource loader. */
public final class ClasspathManifestResources implements ManifestResources {
    public List<Resource> scan(String pattern) throws IOException {
        String suffix = pattern.endsWith("*.yaml") ? ".yaml" : ".yml";
        var found = new TreeMap<String,URL>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = getClass().getClassLoader();
        var roots = loader.getResources("META-INF/skills");
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if ("file".equals(root.getProtocol())) {
                try (var paths = Files.list(Path.of(root.toURI()))) {
                    paths.filter(p -> p.getFileName().toString().endsWith(suffix)).forEach(p -> {
                        try { URL url=p.toUri().toURL(); found.put(url.toString(),url); }
                        catch (Exception e) { throw new IllegalStateException(e); }
                    });
                } catch (java.net.URISyntaxException e) { throw new IOException(e); }
            } else if (root.openConnection() instanceof java.net.JarURLConnection jar) {
                jar.setUseCaches(false);
                try (var archive=jar.getJarFile()) {
                    var entries=archive.entries();
                    while (entries.hasMoreElements()) {
                        String name=entries.nextElement().getName();
                        if (name.startsWith("META-INF/skills/") && name.endsWith(suffix)
                            && !name.substring("META-INF/skills/".length()).contains("/")) {
                            URL url=new URL("jar:"+jar.getJarFileURL()+"!/"+name); found.put(url.toString(),url);
                        }
                    }
                }
            }
        }
        // JAR files need not contain directory entries. URLClassLoader URLs and the
        // application classpath cover such archives even when getResources(root) is empty.
        var archives = new LinkedHashSet<URL>();
        for (ClassLoader current=loader; current!=null; current=current.getParent()) {
            if (current instanceof java.net.URLClassLoader urls) archives.addAll(List.of(urls.getURLs()));
        }
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) archives.add(Path.of(entry).toUri().toURL());
        }
        for (URL archiveUrl : archives) {
            if (!"file".equals(archiveUrl.getProtocol())) continue;
            Path path;
            try { path=Path.of(archiveUrl.toURI()); }
            catch (java.net.URISyntaxException e) { throw new IOException(e); }
            if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) continue;
            try (var archive=new java.util.jar.JarFile(path.toFile())) {
                var entries=archive.entries();
                while (entries.hasMoreElements()) {
                    String name=entries.nextElement().getName();
                    if (name.startsWith("META-INF/skills/") && name.endsWith(suffix)
                        && !name.substring("META-INF/skills/".length()).contains("/")) {
                        URL url=new URL("jar:"+archiveUrl+"!/"+name);
                        found.put(url.toString(),url);
                    }
                }
            }
        }
        var out=new ArrayList<Resource>();
        for (URL url:found.values()) out.add(new Resource() {
            public InputStream getInputStream() throws IOException { return url.openStream(); }
            public String sourcePath() { return url.toString(); }
        });
        return List.copyOf(out);
    }
}
