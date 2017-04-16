package com.jakduk.api.restcontroller;

import com.jakduk.api.common.ApiConst;
import com.jakduk.api.common.util.ApiUtils;
import com.jakduk.api.common.util.UserUtils;
import com.jakduk.api.common.vo.AuthUserProfile;
import com.jakduk.api.restcontroller.vo.EmptyJsonResponse;
import com.jakduk.api.restcontroller.vo.UserFeelingResponse;
import com.jakduk.api.service.GalleryService;
import com.jakduk.api.vo.gallery.GalleriesResponse;
import com.jakduk.api.vo.gallery.GalleryResponse;
import com.jakduk.api.vo.gallery.GalleryUploadResponse;
import com.jakduk.api.vo.gallery.LinkedItemForm;
import com.jakduk.core.common.CoreConst;
import com.jakduk.core.exception.ServiceError;
import com.jakduk.core.exception.ServiceException;
import com.jakduk.core.model.db.Gallery;
import com.jakduk.core.model.embedded.CommonWriter;
import com.jakduk.core.model.simple.GalleryOnList;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author pyohwan
 * 16. 3. 20 오후 11:17
 */

@Api(tags = "Gallery", description = "사진첩 API")
@RequestMapping("/api")
@RestController
public class GalleryRestController {

    @Value("${api.server.url}")
    private String apiServerUrl;

    @Value("${api.gallery.image.url.path}")
    private String imagePath;

    @Value("${api.gallery.thumbnail.url.path}")
    private String thumbnailPath;

    @Autowired
    private GalleryService galleryService;

    @Resource
    private ApiUtils apiUtils;

    @ApiOperation(value = "사진 목록", response = GalleriesResponse.class)
    @RequestMapping(value = "/galleries", method = RequestMethod.GET)
    public GalleriesResponse getGalleries(@RequestParam(required = false) String id,
                                          @RequestParam(required = false, defaultValue = "0") Integer size) {

        if (size < CoreConst.GALLERY_SIZE) size = CoreConst.GALLERY_SIZE;

        List<GalleryOnList> galleries = galleryService.getGalleriesById(id, size);

        if (ObjectUtils.isEmpty(galleries))
            throw new ServiceException(ServiceError.NOT_FOUND);

        List<ObjectId> ids = galleries.stream()
                .map(gallery -> new ObjectId(gallery.getId()))
                .collect(Collectors.toList());

        Map<String, Integer> usersLikingCount = galleryService.getGalleryUsersLikingCount(ids);
        Map<String, Integer> usersDislikingCount = galleryService.getGalleryUsersDislikingCount(ids);

        GalleriesResponse response = new GalleriesResponse();
        response.setGalleries(galleries);
        response.setUsersLikingCount(usersLikingCount);
        response.setUsersDislikingCount(usersDislikingCount);

        return response;
    }

    @ApiOperation(value = "사진 올리기")
    @PostMapping("/gallery")
    public GalleryUploadResponse uploadImage(@RequestParam MultipartFile file) throws IOException {

        if (file.isEmpty())
            throw new ServiceException(ServiceError.INVALID_PARAMETER);

        String contentType = file.getContentType();

        if (! StringUtils.startsWithIgnoreCase(contentType, "image/"))
            throw new ServiceException(ServiceError.FILE_ONLY_IMAGE_TYPE_CAN_BE_UPLOADED);

        CommonWriter commonWriter = UserUtils.getCommonWriter();

        Gallery gallery = galleryService.uploadImage(commonWriter, file.getOriginalFilename(), file.getSize(),
                contentType, file.getBytes());

        GalleryUploadResponse response = new GalleryUploadResponse();

        BeanUtils.copyProperties(gallery, response);
        response.setImageUrl(apiUtils.generateGalleryUrl(CoreConst.IMAGE_SIZE_TYPE.LARGE, gallery.getId()));
        response.setThumbnailUrl(apiUtils.generateGalleryUrl(CoreConst.IMAGE_SIZE_TYPE.SMALL, gallery.getId()));

        return response;
    }

    @ApiOperation(value = "사진 지움")
    @DeleteMapping("/gallery/{id}")
    public EmptyJsonResponse removeImage(
            @ApiParam(value = "사진 ID", required = true) @PathVariable String id,
            @ApiParam(value = "연관된 아이템 폼") @RequestBody(required = false) LinkedItemForm form,
            HttpServletRequest request) {

        if (! UserUtils.isUser())
            throw new ServiceException(ServiceError.UNAUTHORIZED_ACCESS);

        AuthUserProfile authUserProfile = UserUtils.getAuthUserProfile();

        galleryService.removeImage(id, authUserProfile.getId(), Objects.nonNull(form) ? form.getItemId() : null,
                Objects.nonNull(form) ? form.getFrom() : null);

        // form이 null이 아니면 글, 댓글 편집시 호출 했기 때문에 gallery를 바로 지우면 안된다. 글/댓글 편집 완료 시 실제로 gallery를 지워야 한다.
        // session에 저장해 두자.
        if (Objects.nonNull(form)) {
            HttpSession httpSession = request.getSession();
            String name = form.getFrom() + ":" + form.getItemId() +  ":galleries_for_removal";

            List<String> galleryIds = (List<String>) httpSession.getAttribute(name);

            if (ObjectUtils.isEmpty(galleryIds))
                galleryIds = new ArrayList<>();

            if (! galleryIds.contains(id))
                galleryIds.add(id);

            httpSession.setAttribute(name, galleryIds);
//            httpSession.setMaxInactiveInterval(10);
        }

        return EmptyJsonResponse.newInstance();
    }

    @ApiOperation(value = "사진 상세")
    @GetMapping("/gallery/{id}")
    public GalleryResponse view(
            @ApiParam(value = "Gallery ID", required = true) @PathVariable String id,
            HttpServletRequest request,
            HttpServletResponse response) {

        HttpSession httpSession = request.getSession();
        System.out.println("phjang=" + httpSession.getAttribute("aaaa"));

        request.getSession().setAttribute("aaaa", "ddddd");
        request.getSession().setMaxInactiveInterval(10);

        Boolean isAddCookie = ApiUtils.addViewsCookie(request, response, ApiConst.VIEWS_COOKIE_TYPE.GALLERY, id);

        return galleryService.getGalleryDetail(id, isAddCookie);

    }

    @ApiOperation(value = "사진 좋아요 싫어요")
    @RequestMapping(value = "/gallery/{id}/{feeling}", method = RequestMethod.POST)
    public UserFeelingResponse setGalleryFeeling(@PathVariable String id, @PathVariable CoreConst.FEELING_TYPE feeling) {

        if (! UserUtils.isUser())
            throw new ServiceException(ServiceError.UNAUTHORIZED_ACCESS);

        CommonWriter writer = UserUtils.getCommonWriter();

        Map<String, Object> data = galleryService.setUserFeeling(writer, id, feeling);

        return UserFeelingResponse.builder()
          .myFeeling((CoreConst.FEELING_TYPE) data.get("feeling"))
          .numberOfLike((Integer) data.get("numberOfLike"))
          .numberOfDislike((Integer) data.get("numberOfDislike"))
          .build();
    }

}
