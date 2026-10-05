package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageNormalizerTest {

    @Test
    void isMediaDetectsImageTypes() {
        assertTrue(ImageNormalizer.isMedia("image/png"));
        assertTrue(ImageNormalizer.isMedia("image/jpeg"));
        assertTrue(ImageNormalizer.isMedia("image/gif"));
        assertTrue(ImageNormalizer.isMedia("image/webp"));
    }

    @Test
    void isMediaRejectsNonImage() {
        assertFalse(ImageNormalizer.isMedia("text/plain"));
        assertFalse(ImageNormalizer.isMedia("application/json"));
        assertFalse(ImageNormalizer.isMedia(null));
    }

    @Test
    void anthropicSupportsStandardTypes() {
        assertTrue(ImageNormalizer.supportsMediaInToolResult("anthropic", "image/png"));
        assertTrue(ImageNormalizer.supportsMediaInToolResult("anthropic", "image/jpeg"));
        assertFalse(ImageNormalizer.supportsMediaInToolResult("anthropic", "image/heic"));
    }

    @Test
    void googleSupportsExtraTypes() {
        assertTrue(ImageNormalizer.supportsMediaInToolResult("google", "image/heic"));
        assertTrue(ImageNormalizer.supportsMediaInToolResult("gemini", "image/heif"));
    }

    @Test
    void isSupportedByProvider() {
        assertTrue(ImageNormalizer.isSupportedByProvider("anthropic", "image/png"));
        assertTrue(ImageNormalizer.isSupportedByProvider("openai", "image/jpeg"));
        assertFalse(ImageNormalizer.isSupportedByProvider("anthropic", "image/heic"));
        assertFalse(ImageNormalizer.isSupportedByProvider("openai", "text/html"));
    }

    @Test
    void parseDataUri() {
        var components = ImageNormalizer.parseDataUri("data:image/png;base64,iVBORw0KGg==");
        assertNotNull(components);
        assertEquals("image/png", components.mime());
        assertEquals("base64", components.encoding());
        assertEquals("iVBORw0KGg==", components.data());
    }

    @Test
    void parseDataUriInvalid() {
        assertNull(ImageNormalizer.parseDataUri(null));
        assertNull(ImageNormalizer.parseDataUri("not-a-data-uri"));
        assertNull(ImageNormalizer.parseDataUri("data:incomplete"));
    }

    @Test
    void decodeBase64DataUri() {
        var components = ImageNormalizer.parseDataUri("data:image/png;base64,aGVsbG8=");
        assertNotNull(components);
        byte[] decoded = components.decode();
        assertEquals("hello", new String(decoded));
    }
}
