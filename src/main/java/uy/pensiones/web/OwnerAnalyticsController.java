package uy.pensiones.web;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.OwnerAnalyticsService;

@RestController
@RequestMapping("/api/owner/analytics")
public class OwnerAnalyticsController {
    private final OwnerAnalyticsService analytics;
    private final UserRepository users;
    public OwnerAnalyticsController(OwnerAnalyticsService analytics, UserRepository users){this.analytics=analytics;this.users=users;}

    @GetMapping("/pensions/{pensionId}")
    public OwnerAnalyticsService.PensionAnalytics pension(@AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long pensionId, @RequestParam(defaultValue="30") int days){return analytics.pension(userId(principal),pensionId,days);}

    @GetMapping("/portfolio")
    public OwnerAnalyticsService.PortfolioAnalytics portfolio(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam(defaultValue="30") int days){return analytics.portfolio(userId(principal),days);}

    @GetMapping(value="/export", produces="text/csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam(defaultValue="30") int days){
        var file=analytics.export(userId(principal),days);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.filename()+"\"").body(file.bytes());
    }

    private Long userId(OAuth2User principal){
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Debes iniciar sesión para continuar");
        Object app=principal.getAttribute("appUser"); if(app instanceof User u && u.getId()!=null)return u.getId();
        String email=principal.getAttribute("email");
        if(email==null||email.isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"No se pudo identificar al usuario autenticado");
        return users.findByEmail(email).map(User::getId).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"No se pudo identificar al usuario autenticado"));
    }
}
