package uy.pensiones.web;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.service.PensionMediaService;
import uy.pensiones.service.PensionMediaDtoMapper;
import uy.pensiones.web.dto.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/pensions/{id}/media")
public class PensionMediaController {

    private final PensionRepository pensions;
    private final PensionMediaRepository mediaRepo;
    private final PensionMediaService mediaService;
    private final PensionMediaDtoMapper mediaMapper;

    public PensionMediaController(PensionRepository pensions,
                                  PensionMediaRepository mediaRepo,
                                  PensionMediaService mediaService,
                                  PensionMediaDtoMapper mediaMapper) {
        this.pensions = pensions;
        this.mediaRepo = mediaRepo;
        this.mediaService = mediaService;
        this.mediaMapper = mediaMapper;
    }


    private Pension getPension(Long id) {
        return pensions.findById(id).orElseThrow();
    }

    @GetMapping
    @PreAuthorize("@authz.canViewPension(#principal, #id)")
    public List<PensionMediaDTO> list(@AuthenticationPrincipal OAuth2User principal,
                                      @PathVariable Long id) {
        return mediaRepo.findByPensionIdOrderBySortOrderAscIdAsc(id).stream().map(mediaMapper::toDto).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public List<PensionMediaDTO> upload(@AuthenticationPrincipal OAuth2User principal,
                                        @PathVariable Long id,
                                        @RequestParam("files") List<MultipartFile> files) throws IOException {
        Pension p = getPension(id);
        return mediaService.upload(p, files).stream().map(mediaMapper::toDto).toList();
    }

    @PostMapping("/link")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public PensionMediaDTO addLink(@AuthenticationPrincipal OAuth2User principal,
                                   @PathVariable Long id,
                                   @RequestBody MediaLinkReq req) {
        Pension p = getPension(id);
        return mediaMapper.toDto(mediaService.addYoutubeLink(p, req.url()));
    }

    @PatchMapping("/order")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public void reorder(@AuthenticationPrincipal OAuth2User principal,
                        @PathVariable Long id,
                        @RequestBody MediaOrderReq req) {
        mediaService.reorder(id, req.ids());
    }

    @PatchMapping("/{mediaId}")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public PensionMediaDTO update(@AuthenticationPrincipal OAuth2User principal,
                                  @PathVariable Long id,
                                  @PathVariable Long mediaId,
                                  @RequestBody MediaUpdateReq req) {
        var m = mediaService.setCover(id, mediaId, Boolean.TRUE.equals(req.cover()));
        return mediaMapper.toDto(m);
    }

    @DeleteMapping("/{mediaId}")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public void delete(@AuthenticationPrincipal OAuth2User principal,
                       @PathVariable Long id,
                       @PathVariable Long mediaId) throws IOException {
        mediaService.delete(id, mediaId);
    }
}
