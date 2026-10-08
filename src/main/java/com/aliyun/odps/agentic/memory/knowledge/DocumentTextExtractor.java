package com.aliyun.odps.agentic.memory.knowledge;
import java.nio.file.Path;
import java.util.Set;
public interface DocumentTextExtractor {
    Set<String> SUPPORTED_EXTS = Set.of("docx","doc","xlsx","pdf","pptx",
        "md","txt","csv","json","xml","yaml","yml","log","sql","java","py","js","ts","tsx","jsx","go","rs","cpp","c","h","sh");
    public record ExtractResult(boolean ok, String text, java.util.List<String> headings, String status, String error) {
        public static ExtractResult ok(String text, java.util.List<String> headings) {
            return new ExtractResult(true, text, headings, "ok", null);
        }
        public static ExtractResult failed(String status, String error) {
            return new ExtractResult(false, null, java.util.List.of(), status, error);
        }
    }

    ExtractResult extract(Path path);
    static String extOf(String filename) {
        int dot=filename.lastIndexOf('.');
        return dot<0 ? "" : filename.substring(dot+1).toLowerCase(java.util.Locale.ROOT);
    }
}
