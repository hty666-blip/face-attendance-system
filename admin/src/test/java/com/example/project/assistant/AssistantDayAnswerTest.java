package com.example.project.assistant;

import com.example.project.entity.AssistantToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 纯回执渲染测试，不启动数据库或模型服务。 */
class AssistantDayAnswerTest {
    private final ObjectMapper json = new ObjectMapper();

    private String answer(Object status, Object missingIn, Object missingOut) throws Exception {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("businessDate", "2026-10-26");
        data.put("status", status);
        data.put("missingIn", missingIn);
        data.put("missingOut", missingOut);
        data.put("lateMinutes", 15);
        data.put("earlyMinutes", 15);
        data.put("leaveDays", 0.5);
        AssistantToolCall receipt = new AssistantToolCall();
        receipt.setToolName("explain_attendance_day");
        receipt.setState("SUCCEEDED");
        receipt.setResultJson(json.writeValueAsString(data));
        AssistantService service = new AssistantService();
        ReflectionTestUtils.setField(service, "json", json);
        return ReflectionTestUtils.invokeMethod(service, "factAnswer", Collections.singletonList(receipt), "COMPLETED", null);
    }

    @Test void realStatusesUseSameChineseNamesAsAttendancePage() throws Exception {
        String[][] cases = {{"NORMAL","正常"}, {"LATE","迟到"}, {"EARLY","早退"},
                {"LATE_AND_EARLY","迟到且早退"}, {"MISSING","待核对 / 缺卡"}, {"LEAVE","已请假"},
                {"IN_PROGRESS","进行中"}, {"REST","休息日"}, {"UPCOMING","尚未到来"},
                {"LEGACY","旧数据不推算"}};
        for (String[] item : cases) {
            String text = answer(item[0], false, false);
            assertTrue(text.contains("本人日结果：" + item[1] + "，"), item[0]);
            assertTrue(text.contains("2026-10-26") && text.contains("迟到15分钟，早退15分钟，请假0.5天"), item[0]);
            assertFalse(text.contains(item[0]), item[0]);
        }
    }

    @Test void restrictedAndMissingPriorityMatchesPage() throws Exception {
        String restricted = answer("RESTRICTED", true, true);
        assertTrue(restricted.contains("本人日结果：无权查看历史。"));
        assertFalse(restricted.contains("迟到15分钟"));
        assertTrue(answer("NORMAL", false, true).contains("本人日结果：待核对 / 缺卡，"));
    }

    @Test void unknownMissingOrMalformedStatusDoesNotInventAResultOrExposeRawText() throws Exception {
        for (Object status : new Object[]{null, "", "UNEXPECTED_PRIVATE_VALUE", 123}) {
            String text = answer(status, "true", null);
            assertTrue(text.contains("本人日结果：待核对，"));
            assertFalse(text.contains("UNEXPECTED_PRIVATE_VALUE"));
        }
    }
}
