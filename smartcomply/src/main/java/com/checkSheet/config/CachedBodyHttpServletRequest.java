package com.checkSheet.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.*;

public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        if(request instanceof org.springframework.web.multipart.MultipartException) {
            cachedBody = "".getBytes();
        } else {
            cachedBody = convertRequestToByteArray(request);
        }
    }
    public static byte[] convertRequestToByteArray(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream inputStream = null;
        try {
            inputStream = request.getInputStream();
            byte[] byteChunk = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(byteChunk)) != -1) {
                buffer.write(byteChunk, 0, bytesRead);
            }
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
        return buffer.toByteArray();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return new CachedServletInputStream(new ByteArrayInputStream(cachedBody));
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }

    public String getRequestBody() {
        return new String(cachedBody);
    }

    private static class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream buffer;

        public CachedServletInputStream(ByteArrayInputStream buffer) {
            this.buffer = buffer;
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // No operation
        }

        @Override
        public int read() throws IOException {
            return buffer.read();
        }
    }
}
