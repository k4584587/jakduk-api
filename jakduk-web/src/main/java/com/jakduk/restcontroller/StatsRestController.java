package com.jakduk.restcontroller;

import com.jakduk.common.CommonConst;
import com.jakduk.model.db.AttendanceClub;
import com.jakduk.model.db.AttendanceLeague;
import com.jakduk.service.CommonService;
import com.jakduk.service.StatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.LocaleResolver;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Created by pyohwan on 16. 3. 20.
 */

@RestController
@RequestMapping("/api/stats")
@Slf4j
public class StatsRestController {

    @Resource
    LocaleResolver localeResolver;

    @Autowired
    private StatsService statsService;

    @Autowired
    private CommonService commonService;

    @RequestMapping(value = "/attendance/league/{league}", method = RequestMethod.GET)
    public List<AttendanceLeague> getAttendancesLeague(@PathVariable String league,
                                                       HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);

        if (Objects.isNull(league) || league.isEmpty())
            throw new IllegalArgumentException(commonService.getResourceBundleMessage(locale, "messages.common", "common.exception.invalid.parameter"));

        List<AttendanceLeague> attendances = statsService.getAttendanceLeague(league);

        return attendances;
    }

    @RequestMapping(value = "/attendance/club/{clubOrigin}", method = RequestMethod.GET)
    public List<AttendanceClub> getAttendancesClub(@PathVariable String clubOrigin,
                                                  HttpServletRequest request) {

        Locale locale = localeResolver.resolveLocale(request);

        if (Objects.isNull(clubOrigin) || clubOrigin.isEmpty())
            throw new IllegalArgumentException(commonService.getResourceBundleMessage(locale, "messages.common", "common.exception.invalid.parameter"));

        List<AttendanceClub> attendances = statsService.getAttendanceClub(locale, clubOrigin);

        return attendances;
    }

    @RequestMapping(value = "/attendance/season/{season}", method = RequestMethod.GET)
    public List<AttendanceClub> dataAttendanceSeason(@PathVariable Integer season,
                                     @RequestParam(required = false, defaultValue = CommonConst.K_LEAGUE_ABBREVIATION) String league){

        List<AttendanceClub> attendances = statsService.getAttendancesSeason(season, league);

        return attendances;
    }
}