package com.roydon.dear.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数学计算工具：为 agent 提供可靠的数学计算能力。
 * <p>
 * 使用 {@link BigDecimal} 保证精度，覆盖四则运算、括号、幂运算与百分比计算；
 * 对除零、非法表达式、非法输入返回友好的错误信息而非直接抛异常。
 * </p>
 */
@Service
@Slf4j
public class MathTools {

    private static final int DIVISION_SCALE = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Tool(name = "calculate", description = "计算数学表达式，支持四则运算（+ - * /）、括号、幂运算（^）与百分比（%，N% 表示 N/100）。例如：\"1+2*3\"=7、\"(1+2)*3\"=9、\"2^10\"=1024、\"200*20%\"=40。使用 BigDecimal 保证精度。/ Evaluate a mathematical expression supporting + - * / ^ ( ) and % (N% = N/100). e.g. 1+2*3, (1+2)*3, 2^10, 200*20%. Precision via BigDecimal.")
    public String calculate(@ToolParam(description = "要计算的数学表达式，如 \"1+2*3\"", required = true) String expression) {
        log.info("EXECUTE Tool: calculate: {}", expression);
        if (expression == null || expression.trim().isEmpty()) {
            return "计算错误：表达式不能为空 / Expression must not be empty";
        }
        try {
            BigDecimal result = new MathExpressionParser(expression).parse();
            return format(result);
        } catch (MathExpressionParser.MathExpressionException e) {
            log.warn("计算表达式失败: expression={}, reason={}", expression, e.getMessage());
            return "计算错误：" + e.getMessage();
        } catch (ArithmeticException e) {
            log.warn("计算表达式出现算术异常: expression={}, reason={}", expression, e.getMessage());
            return "计算错误：数值运算异常（" + e.getMessage() + "）";
        } catch (Exception e) {
            log.error("计算表达式发生未知异常: expression={}", expression, e);
            return "计算错误：未知异常（" + e.getMessage() + "）";
        }
    }

    @Tool(name = "percentage_of", description = "求某个数值的百分之几：返回 value * percent / 100。例如 percentage_of(200, 20)=40，表示 200 的 20% 等于 40。/ Calculate percent percent of value: value * percent / 100. e.g. percentage_of(200, 20)=40.")
    public String percentageOf(
            @ToolParam(description = "基础数值", required = true) BigDecimal value,
            @ToolParam(description = "百分比数值（如 20 表示 20%）", required = true) BigDecimal percent) {
        log.info("EXECUTE Tool: percentage_of: value={}, percent={}", value, percent);
        if (value == null || percent == null) {
            return "计算错误：value 和 percent 均不能为空 / value and percent are required";
        }
        try {
            BigDecimal result = value.multiply(percent).divide(HUNDRED, DIVISION_SCALE, RoundingMode.HALF_UP);
            return format(result);
        } catch (ArithmeticException e) {
            log.warn("percentage_of 计算异常: value={}, percent={}, reason={}", value, percent, e.getMessage());
            return "计算错误：" + e.getMessage();
        }
    }

    @Tool(name = "percentage_ratio", description = "求部分占总数的百分比：返回 part / whole * 100。例如 percentage_ratio(20, 200)=10，表示 20 占 200 的 10%。/ Calculate what percentage part is of whole: part / whole * 100. e.g. percentage_ratio(20, 200)=10.")
    public String percentageRatio(
            @ToolParam(description = "部分值", required = true) BigDecimal part,
            @ToolParam(description = "总值（不能为 0）", required = true) BigDecimal whole) {
        log.info("EXECUTE Tool: percentage_ratio: part={}, whole={}", part, whole);
        if (part == null || whole == null) {
            return "计算错误：part 和 whole 均不能为空 / part and whole are required";
        }
        if (whole.compareTo(BigDecimal.ZERO) == 0) {
            return "计算错误：总值（whole）不能为零 / whole must not be zero";
        }
        try {
            BigDecimal result = part.multiply(HUNDRED).divide(whole, DIVISION_SCALE, RoundingMode.HALF_UP);
            return format(result);
        } catch (ArithmeticException e) {
            log.warn("percentage_ratio 计算异常: part={}, whole={}, reason={}", part, whole, e.getMessage());
            return "计算错误：" + e.getMessage();
        }
    }

    @Tool(name = "percentage_change", description = "求从旧值到新值的变化百分比（增长率/下降率）：返回 (newValue - oldValue) / oldValue * 100。例如 percentage_change(100, 150)=50，表示从 100 增长到 150 增长了 50%。/ Calculate percentage change from oldValue to newValue: (newValue - oldValue) / oldValue * 100. e.g. percentage_change(100, 150)=50.")
    public String percentageChange(
            @ToolParam(description = "旧值", required = true) BigDecimal oldValue,
            @ToolParam(description = "新值", required = true) BigDecimal newValue) {
        log.info("EXECUTE Tool: percentage_change: oldValue={}, newValue={}", oldValue, newValue);
        if (oldValue == null || newValue == null) {
            return "计算错误：oldValue 和 newValue 均不能为空 / oldValue and newValue are required";
        }
        if (oldValue.compareTo(BigDecimal.ZERO) == 0) {
            return "计算错误：旧值（oldValue）不能为零 / oldValue must not be zero";
        }
        try {
            BigDecimal result = newValue.subtract(oldValue)
                    .multiply(HUNDRED)
                    .divide(oldValue, DIVISION_SCALE, RoundingMode.HALF_UP);
            return format(result);
        } catch (ArithmeticException e) {
            log.warn("percentage_change 计算异常: oldValue={}, newValue={}, reason={}", oldValue, newValue, e.getMessage());
            return "计算错误：" + e.getMessage();
        }
    }

    /** 去除结果尾部多余的 0，并避免科学计数法展示 */
    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
