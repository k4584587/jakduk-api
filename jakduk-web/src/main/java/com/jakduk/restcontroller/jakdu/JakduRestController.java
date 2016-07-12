package com.jakduk.restcontroller.jakdu;

import com.jakduk.common.CommonConst;
import com.jakduk.exception.UnauthorizedAccessException;
import com.jakduk.model.db.*;
import com.jakduk.model.embedded.LocalName;
import com.jakduk.model.web.jakdu.JakduCommentWriteRequest;
import com.jakduk.model.web.jakdu.JakduCommentsResponse;
import com.jakduk.model.web.jakdu.MyJakduRequest;
import com.jakduk.service.CommonService;
import com.jakduk.service.FootballService;
import com.jakduk.service.JakduService;
import com.jakduk.restcontroller.vo.JakduScheduleResponse;
import com.jakduk.restcontroller.vo.UserFeelingResponse;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mobile.device.Device;
import org.springframework.mobile.device.DeviceUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.LocaleResolver;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by pyohwan on 16. 3. 4.
 */

@Slf4j
@RestController
@RequestMapping("/api/jakdu")
public class JakduRestController {

    @Resource
    LocaleResolver localeResolver;

    @Autowired
    private JakduService jakduService;

    @Autowired
    private CommonService commonService;

    @Autowired
    private FootballService footballService;

    // /jakdu/write에서 쓰이는데 안쓸것 같다.
    @RequestMapping(value = "/data", method = RequestMethod.GET)
    public Map data(HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);
        Map<String, Object> result = jakduService.getDataWrite(locale);

        return result;
    }

    // 작두 일정 목록
    @RequestMapping(value = "/schedule", method = RequestMethod.GET)
    public JakduScheduleResponse dataSchedule(@RequestParam(required = false, defaultValue = "1") int page,
                                              @RequestParam(required = false, defaultValue = "20") int size,
                                              HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);
        String language = commonService.getLanguageCode(locale, null);

        Sort sort = new Sort(Sort.Direction.ASC, Arrays.asList("group", "date"));
        Pageable pageable = new PageRequest(page - 1, size, sort);

        Set<ObjectId> fcIds = new HashSet<>();
        Set<ObjectId> competitionIds = new HashSet<>();
        List<JakduSchedule> jakduSchedules = jakduService.findAll(pageable).getContent();

        for (JakduSchedule jakduSchedule : jakduSchedules) {
            fcIds.add(new ObjectId(jakduSchedule.getHome().getId()));
            fcIds.add(new ObjectId(jakduSchedule.getAway().getId()));

            if (Objects.nonNull(jakduSchedule.getCompetition()))
                competitionIds.add(new ObjectId(jakduSchedule.getCompetition().getId()));
        }

        List<FootballClub> footballClubs = footballService.getFootballClubs(new ArrayList<>(fcIds), language, CommonConst.NAME_TYPE.fullName);
        List<Competition> competitions = footballService.getCompetitions(new ArrayList<>(competitionIds), language);

        Map<String, LocalName> fcNames = footballClubs.stream()
                .collect(Collectors.toMap(footballClub -> footballClub.getOrigin().getId(), footballClub -> footballClub.getNames().get(0)));

        Map<String, LocalName> competitionNames = competitions.stream()
                .collect(Collectors.toMap(Competition::getId, competition -> competition.getNames().get(0)));

        JakduScheduleResponse response = new JakduScheduleResponse();
        response.setJakduSchedules(jakduSchedules);
        response.setFcNames(fcNames);
        response.setCompetitionNames(competitionNames);

        return response;
    }

    // 작두 일정 데이터 하나 가져오기.
    @RequestMapping(value = "/schedule/{id}", method = RequestMethod.GET)
    public Map<String, Object> dataSchedule(@PathVariable String id,
                                            HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);

        Map<String, Object> result = new HashMap<>();

        JakduSchedule jakduSchedule = jakduService.findScheduleById(id);
        result.put("jakduSchedule", jakduSchedule);

        if (commonService.isUser() == true) {
            result.put("myJakdu", jakduService.getMyJakdu(locale, id));
        }

        return result;
    }

    // 작두 타기
    @RequestMapping(value ="/myJakdu", method = RequestMethod.POST)
    public Jakdu myJakduWrite(@RequestBody MyJakduRequest myJakdu, HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);

        if (commonService.isUser() == false)
            throw new UnauthorizedAccessException(commonService.getResourceBundleMessage(locale, "messages.common", "common.exception.access.denied"));

        if (Objects.isNull(myJakdu)) {
            throw new IllegalArgumentException(commonService.getResourceBundleMessage(locale, "messages.common", "common.exception.invalid.parameter"));
        }

        Jakdu jakdu = jakduService.setMyJakdu(locale, myJakdu);

        return jakdu;
    }

    // 작두 댓글 달기
    @RequestMapping(value ="/schedule/comment", method = RequestMethod.POST)
    public JakduComment commentWrite(@RequestBody JakduCommentWriteRequest jakduCommentWriteRequest,
                                     HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);

        if (Objects.isNull(jakduCommentWriteRequest)) {
            throw new IllegalArgumentException(commonService.getResourceBundleMessage(locale, "messages.common", "common.exception.invalid.parameter"));
        }

        Device device = DeviceUtils.getCurrentDevice(request);
        jakduCommentWriteRequest.setDevice(commonService.getDeviceInfo(device));

        JakduComment jakduComment = jakduService.setComment(locale, jakduCommentWriteRequest);

        return jakduComment;
    }

    // 작두 댓글 목록
    @RequestMapping(value = "/schedule/comments/{jakduScheduleId}", method = RequestMethod.GET)
    public JakduCommentsResponse getComments(@PathVariable String jakduScheduleId,
                                             @RequestParam(required = false) String commentId) {

        JakduCommentsResponse response = jakduService.getComments(jakduScheduleId, commentId);

        return response;
    }

    // 작두 댓글 좋아요 싫어요
    @RequestMapping(value = "/schedule/comment/{commentId}/{feeling}", method = RequestMethod.POST)
    public UserFeelingResponse setCommentFeeling(@PathVariable String commentId,
                                                 @PathVariable CommonConst.FEELING_TYPE feeling,
                                                 HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);

        JakduComment jakduComment = jakduService.setJakduCommentFeeling(locale, commentId, feeling);

        Integer numberOfLike = Objects.nonNull(jakduComment.getUsersLiking()) ? jakduComment.getUsersLiking().size() : 0;
        Integer numberOfDisLike = Objects.nonNull(jakduComment.getUsersDisliking()) ? jakduComment.getUsersDisliking().size() : 0;

        UserFeelingResponse response = new UserFeelingResponse();
        response.setFeeling(feeling);
        response.setNumberOfLike(numberOfLike);
        response.setNumberOfDislike(numberOfDisLike);

        return response;
    }
}