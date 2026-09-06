package uy.pensiones.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import uy.pensiones.web.error.ApiErrorWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicPayloadLimitFilter extends OncePerRequestFilter {

    private final int maxPayloadBytes;
    private final ObjectMapper objectMapper;

    public PublicPayloadLimitFilter(
            @Value("${app.inquiries.max-payload-bytes:16384}") int maxPayloadBytes,
            ObjectMapper objectMapper) {
        this.maxPayloadBytes = Math.max(1024, maxPayloadBytes);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath();
        return path == null || !path.matches("/api/public/pensions/\\d+/(inquiries|reports)/?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxPayloadBytes) {
            reject(request, response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(maxPayloadBytes + 1);
        if (body.length > maxPayloadBytes) {
            reject(request, response);
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiErrorWriter.write(request, response, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE", "La solicitud es demasiado grande.");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return input.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener == null) return;
                    try {
                        if (isFinished()) readListener.onAllDataRead();
                        else readListener.onDataAvailable();
                    } catch (IOException e) {
                        readListener.onError(e);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
