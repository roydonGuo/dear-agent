package com.roydon.dear.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 使用 BigDecimal 实现的数学表达式解析器（递归下降法）。
 * <p>
 * 支持：四则运算（+ - * /）、括号、幂运算（^，右结合）、后置百分比（%，N% = N/100）。
 * 纯 Java 实现，不执行外部脚本、不通过反射调用任意方法，避免表达式注入等不安全求值。
 * </p>
 */
final class MathExpressionParser {

    /** 表达式解析/求值异常，message 面向 agent 展示，应为友好中文提示 */
    static final class MathExpressionException extends RuntimeException {
        MathExpressionException(String message) {
            super(message);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 除法结果保留的小数位数 */
    private static final int DIVISION_SCALE = 10;

    private final String src;
    private int pos;

    MathExpressionParser(String src) {
        this.src = src;
    }

    BigDecimal parse() {
        if (src == null || src.trim().isEmpty()) {
            throw new MathExpressionException("表达式不能为空");
        }
        BigDecimal result = additive();
        skipWhitespace();
        if (pos < src.length()) {
            throw new MathExpressionException("存在无法识别的字符: '" + src.charAt(pos) + "'");
        }
        return result;
    }

    /** 加法 / 减法（优先级最低） */
    private BigDecimal additive() {
        BigDecimal left = multiplicative();
        while (true) {
            char c = peek();
            if (c == '+') {
                next();
                left = left.add(multiplicative());
            } else if (c == '-') {
                next();
                left = left.subtract(multiplicative());
            } else {
                break;
            }
        }
        return left;
    }

    /** 乘法 / 除法 */
    private BigDecimal multiplicative() {
        BigDecimal left = unary();
        while (true) {
            char c = peek();
            if (c == '*') {
                next();
                left = left.multiply(unary());
            } else if (c == '/') {
                next();
                BigDecimal right = unary();
                if (right.compareTo(BigDecimal.ZERO) == 0) {
                    throw new MathExpressionException("除数不能为零");
                }
                left = left.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP);
            } else {
                break;
            }
        }
        return left;
    }

    /** 一元正负号：-2^2 = -(2^2) = -4 */
    private BigDecimal unary() {
        char c = peek();
        if (c == '+') {
            next();
            return unary();
        }
        if (c == '-') {
            next();
            return unary().negate();
        }
        return power();
    }

    /** 幂运算，右结合：2^3^2 = 2^(3^2) = 512 */
    private BigDecimal power() {
        BigDecimal base = primary();
        if (peek() == '^') {
            next();
            BigDecimal exponent = unary();
            return pow(base, exponent);
        }
        return base;
    }

    /** 数字或括号表达式，支持后置 % */
    private BigDecimal primary() {
        char c = peek();
        if (c == '(') {
            next();
            BigDecimal value = additive();
            char close = next();
            if (close != ')') {
                throw new MathExpressionException("括号不匹配，缺少右括号 ')'");
            }
            return applyPercent(value);
        }
        if (c == '\0') {
            throw new MathExpressionException("表达式不完整，缺少操作数");
        }
        if (Character.isDigit(c) || c == '.') {
            return applyPercent(readNumber());
        }
        throw new MathExpressionException("无法识别的字符: '" + c + "'");
    }

    /** 后置 %：N% = N / 100 */
    private BigDecimal applyPercent(BigDecimal value) {
        while (peek() == '%') {
            next();
            value = value.divide(HUNDRED, DIVISION_SCALE, RoundingMode.HALF_UP);
        }
        return value;
    }

    /** 读取数字字面量，支持小数（如 3.14 / .5 / 5.） */
    private BigDecimal readNumber() {
        int start = pos;
        boolean hasDigit = false;
        boolean hasDot = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isDigit(c)) {
                hasDigit = true;
                pos++;
            } else if (c == '.') {
                if (hasDot) {
                    throw new MathExpressionException("数字格式错误：存在多个小数点");
                }
                hasDot = true;
                pos++;
            } else {
                break;
            }
        }
        if (!hasDigit) {
            throw new MathExpressionException("数字格式错误");
        }
        String numStr = src.substring(start, pos);
        try {
            return new BigDecimal(numStr);
        } catch (NumberFormatException e) {
            throw new MathExpressionException("数字格式错误: '" + numStr + "'");
        }
    }

    /**
     * BigDecimal 幂运算：整数指数使用精确的 {@link BigDecimal#pow(int)}；
     * 非整数指数（如 2^0.5）回退到 double 计算后转回 BigDecimal。
     */
    private BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        if (base.compareTo(BigDecimal.ZERO) == 0 && exponent.signum() < 0) {
            throw new MathExpressionException("0 的负次幂无意义");
        }
        if (exponent.stripTrailingZeros().scale() <= 0) {
            int intExp = exponent.intValueExact();
            if (intExp >= 0) {
                return base.pow(intExp);
            }
            return BigDecimal.ONE.divide(base.pow(-intExp), DIVISION_SCALE, RoundingMode.HALF_UP);
        }
        double result = Math.pow(base.doubleValue(), exponent.doubleValue());
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new MathExpressionException("幂运算结果无效");
        }
        return BigDecimal.valueOf(result).setScale(DIVISION_SCALE, RoundingMode.HALF_UP);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        skipWhitespace();
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private char next() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new MathExpressionException("表达式不完整");
        }
        return src.charAt(pos++);
    }
}
