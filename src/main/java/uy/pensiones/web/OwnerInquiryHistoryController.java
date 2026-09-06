package uy.pensiones.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.OwnerInquiryHistoryService;
import uy.pensiones.web.dto.OwnerInquiryHistoryResponse;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/owner/inquiry-history")
public class OwnerInquiryHistoryController {

    private final OwnerInquiryHistoryService history;
    private final UserRepository users;

    public OwnerInquiryHistoryController(OwnerInquiryHistoryService history, UserRepository users) {
        this.history = history;
        this.users = users;
    }

    @GetMapping
    public OwnerInquiryHistoryResponse history(@AuthenticationPrincipal OAuth2User principal,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) Long pensionId,
                                               @RequestParam(required = false) InquiryStatus status,
                                               @RequestParam(required = false) InquiryClosureReason closureReason,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @RequestParam(required = false, name = "q") String search) {
        return history.history(current(principal), page, size, pensionId, status, closureReason, from, to, search);
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
