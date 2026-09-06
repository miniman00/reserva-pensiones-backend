package uy.pensiones.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

public class SuccessRedirectHandler implements AuthenticationSuccessHandler {

  private final String frontendUrl;

  public SuccessRedirectHandler(String frontendUrl) {
    this.frontendUrl = frontendUrl;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
    response.setStatus(302);
    response.setHeader("Location", frontendUrl + "/dashboard");
  }
}
