package com.aliyun.odps.agentic.skill.manifest;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
@FunctionalInterface public interface ManifestResources {
    interface Resource { InputStream getInputStream() throws IOException; String sourcePath(); }
    List<Resource> scan(String pattern) throws IOException;
}
