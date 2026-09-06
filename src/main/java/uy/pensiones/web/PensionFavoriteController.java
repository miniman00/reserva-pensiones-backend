package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionFavorite;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionFavoriteRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.PublicTrafficProtectionService;
import uy.pensiones.service.PensionCatalogQualityService;
import uy.pensiones.web.dto.PensionFavoriteDTO;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/favorites")
public class PensionFavoriteController {

    private final PensionFavoriteRepository favorites;
    private final PensionRepository pensions;
    private final UserRepository users;
    private final PublicTrafficProtectionService traffic;
    private final PensionCatalogQualityService catalogQuality;

    public PensionFavoriteController(PensionFavoriteRepository favorites,
                                     PensionRepository pensions,
                                     UserRepository users,
                                     PublicTrafficProtectionService traffic,
                                     PensionCatalogQualityService catalogQuality) {
        this.favorites = favorites;
        this.pensions = pensions;
        this.users = users;
        this.traffic = traffic;
        this.catalogQuality = catalogQuality;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PensionFavoriteDTO> mine(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        return favorites.findByUserIdOrderByCreatedAtDesc(me.getId()).stream()
                .filter(favorite -> catalogQuality.isPubliclyVisible(favorite.getPension()))
                .map(PensionFavoriteDTO::of)
                .toList();
    }

    @GetMapping("/ids")
    @Transactional(readOnly = true)
    public Set<Long> ids(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        return favorites.findByUserIdOrderByCreatedAtDesc(me.getId()).stream()
                .filter(favorite -> catalogQuality.isPubliclyVisible(favorite.getPension()))
                .map(favorite -> favorite.getPension().getId())
                .collect(Collectors.toSet());
    }

    @PutMapping("/{pensionId}")
    @Transactional
    public Map<String, Object> add(HttpServletRequest servletRequest,
                                   @AuthenticationPrincipal OAuth2User principal,
                                   @PathVariable Long pensionId) {
        User me = current(principal);
        traffic.checkFavorite(servletRequest, me.getId());
        Pension pension = pensions.findWithOwnerById(pensionId)
                .filter(catalogQuality::isPubliclyVisible)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));

        if (favorites.existsByUserIdAndPensionId(me.getId(), pensionId)) {
            return Map.of("pensionId", pensionId, "favorite", true);
        }

        favorites.save(PensionFavorite.builder().user(me).pension(pension).build());

        return Map.of("pensionId", pensionId, "favorite", true);
    }

    @DeleteMapping("/{pensionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(HttpServletRequest servletRequest,
                       @AuthenticationPrincipal OAuth2User principal,
                       @PathVariable Long pensionId) {
        User me = current(principal);
        traffic.checkFavorite(servletRequest, me.getId());
        favorites.findByUserIdAndPensionId(me.getId(), pensionId)
                .ifPresent(favorites::delete);
    }

    private User current(OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }

        User appUser = principal.getAttribute("appUser");
        if (appUser != null) {
            return users.findById(appUser.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        }

        String email = principal.getAttribute("email");
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        }
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }
}
