package org.apache.dolphinscheduler.service.calendar;

import org.apache.dolphinscheduler.dao.mapper.CalendarDateMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

@ExtendWith(MockitoExtension.class)
public class BusinessCalendarServiceImplTest {

    @InjectMocks
    private BusinessCalendarServiceImpl businessCalendarService;

    @Mock
    private CalendarDateMapper calendarDateMapper;

    private Date createDate(int year, int month, int day, int hour, int minute) {
        LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute);
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
    }

    // =====================================================================
    // 1. calendarId = null  →  走简单 offset 分支 (不查 DB)
    // =====================================================================

    @Test
    public void testResolveBusinessDate_NoCalendar_ZeroOffset() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Date result = businessCalendarService.resolveBusinessDate(null, physicalDate, 0, null);
        Assertions.assertEquals(physicalDate, result);
    }

    @Test
    public void testResolveBusinessDate_NoCalendar_PositiveOffset() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Date result = businessCalendarService.resolveBusinessDate(null, physicalDate, 2, null);
        Assertions.assertEquals(createDate(2023, 10, 12, 10, 0), result);
    }

    @Test
    public void testResolveBusinessDate_NoCalendar_NegativeOffset() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Date result = businessCalendarService.resolveBusinessDate(null, physicalDate, -3, null);
        Assertions.assertEquals(createDate(2023, 10, 7, 10, 0), result);
    }

    @Test
    public void testResolveBusinessDate_NoCalendar_NullOffset() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Date result = businessCalendarService.resolveBusinessDate(null, physicalDate, null, null);
        Assertions.assertEquals(physicalDate, result);
    }

    // =====================================================================
    // 2. calendarId != null, cutoverTime = null  →  直接用 physicalDate 查 DB
    // =====================================================================

    @Test
    public void testResolveBusinessDate_WithCalendar_NoCutover() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Date dbResult = createDate(2023, 10, 12, 0, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(1L),
                Mockito.any(Date.class),
                Mockito.eq(2),
                Mockito.eq(true)))
                .thenReturn(dbResult);

        Date result = businessCalendarService.resolveBusinessDate(1L, physicalDate, 2, null);

        Assertions.assertEquals(dbResult, result);
        Mockito.verify(calendarDateMapper).calculateBusinessDate(
                Mockito.eq(1L), Mockito.any(), Mockito.eq(2), Mockito.eq(true));
    }

    // =====================================================================
    // 3. cutoverTime 未超过 → 不加天
    // =====================================================================

    @Test
    public void testResolveBusinessDate_CutoverNotBreached() {
        // physicalDate = 14:00, cutover = 15:00 → 未超过, 不加天
        Date physicalDate = createDate(2023, 10, 10, 14, 0);
        Date dbResult = createDate(2023, 10, 10, 0, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(1L),
                Mockito.any(Date.class),
                Mockito.eq(0),
                Mockito.eq(true)))
                .thenReturn(dbResult);

        Date result = businessCalendarService.resolveBusinessDate(1L, physicalDate, 0, "15:00");

        Assertions.assertEquals(dbResult, result);
    }

    // =====================================================================
    // 4. cutoverTime 已超过 → 加一天后查 DB
    // =====================================================================

    @Test
    public void testResolveBusinessDate_CutoverBreached() {
        // physicalDate = 16:00, cutover = 15:00 → 超过, base 日期向后 +1天
        Date physicalDate = createDate(2023, 10, 10, 16, 0);
        Date dbResult = createDate(2023, 10, 11, 0, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(1L),
                Mockito.any(Date.class),
                Mockito.eq(0),
                Mockito.eq(true)))
                .thenReturn(dbResult);

        Date result = businessCalendarService.resolveBusinessDate(1L, physicalDate, 0, "15:00");

        Assertions.assertNotNull(result);
        Assertions.assertEquals(dbResult, result);
        Mockito.verify(calendarDateMapper).calculateBusinessDate(
                Mockito.eq(1L), Mockito.any(), Mockito.eq(0), Mockito.eq(true));
    }

    // =====================================================================
    // 5. cutoverTime 正好等于 → isBefore 返回 false → 加天
    // =====================================================================

    @Test
    public void testResolveBusinessDate_CutoverExactlyEqual() {
        // physicalDate = 15:00, cutover = 15:00 → !isBefore(15:00) → true → +1天
        Date physicalDate = createDate(2023, 10, 10, 15, 0);
        Date dbResult = createDate(2023, 10, 11, 0, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(1L),
                Mockito.any(Date.class),
                Mockito.eq(0),
                Mockito.eq(true)))
                .thenReturn(dbResult);

        Date result = businessCalendarService.resolveBusinessDate(1L, physicalDate, 0, "15:00");
        Assertions.assertEquals(dbResult, result);
    }

    // =====================================================================
    // 6. cutoverTime 为空串 → 等同于 null, 不走 cutover 逻辑
    // =====================================================================

    @Test
    public void testResolveBusinessDate_CutoverEmptyString() {
        Date physicalDate = createDate(2023, 10, 10, 16, 0);
        Date dbResult = createDate(2023, 10, 10, 0, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(1L),
                Mockito.any(Date.class),
                Mockito.eq(0),
                Mockito.eq(true)))
                .thenReturn(dbResult);

        Date result = businessCalendarService.resolveBusinessDate(1L, physicalDate, 0, "  ");
        Assertions.assertEquals(dbResult, result);
    }

    // =====================================================================
    // 7. DB 返回 null → 安全降级到 physicalDate
    // =====================================================================

    @Test
    public void testResolveBusinessDate_DbReturnsNull_Fallback() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(99L),
                Mockito.any(Date.class),
                Mockito.eq(0),
                Mockito.eq(true)))
                .thenReturn(null);

        Date result = businessCalendarService.resolveBusinessDate(99L, physicalDate, 0, null);
        Assertions.assertEquals(physicalDate, result, "Should fallback to physicalDate when DB returns null");
    }

    // =====================================================================
    // 8. offsetDays null 时当作 0
    // =====================================================================

    @Test
    public void testResolveBusinessDate_NullOffsetTreatedAsZero() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Date dbResult = createDate(2023, 10, 10, 0, 0);

        Mockito.when(calendarDateMapper.calculateBusinessDate(
                Mockito.eq(1L),
                Mockito.any(Date.class),
                Mockito.eq(0),   // null → 0
                Mockito.eq(true)))
                .thenReturn(dbResult);

        Date result = businessCalendarService.resolveBusinessDate(1L, physicalDate, null, null);
        Assertions.assertEquals(dbResult, result);
    }

    // =====================================================================
    // 9. 非法 cutoverTime 格式 → DateTimeParseException
    // =====================================================================

    @Test
    public void testResolveBusinessDate_InvalidCutoverFormat() {
        Date physicalDate = createDate(2023, 10, 10, 10, 0);
        Assertions.assertThrows(DateTimeParseException.class, () ->
                businessCalendarService.resolveBusinessDate(1L, physicalDate, 0, "INVALID"));
    }
}
