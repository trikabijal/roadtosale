package com.checkSheet.config;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

public class CachedBodyHttpServletResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private PrintWriter writer = new PrintWriter(outputStream);

    public CachedBodyHttpServletResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return new ServletOutputStreamWrapper(outputStream, super.getOutputStream());
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        outputStream.flush();
    }

    public byte[] getContentAsByteArray() {
        return outputStream.toByteArray();
    }

    private static class ServletOutputStreamWrapper extends ServletOutputStream {

        private final ByteArrayOutputStream outputStream;
        private final ServletOutputStream originalOutputStream;

        public ServletOutputStreamWrapper(ByteArrayOutputStream outputStream, ServletOutputStream originalOutputStream) {
            this.outputStream = outputStream;
            this.originalOutputStream = originalOutputStream;
        }

        @Override
        public void write(int b) throws IOException {
            outputStream.write(b);
            originalOutputStream.write(b);
        }

        @Override
        public void flush() throws IOException {
            outputStream.flush();
            originalOutputStream.flush();
        }

        @Override
        public void close() throws IOException {
            outputStream.close();
            originalOutputStream.close();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {

        }

    }
}
