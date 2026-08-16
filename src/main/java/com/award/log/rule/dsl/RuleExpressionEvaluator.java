package com.award.log.rule.dsl;

import com.award.log.rule.dsl.RuleExprBaseVisitor;
import com.award.log.rule.dsl.RuleExprLexer;
import com.award.log.rule.dsl.RuleExprParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RuleExpressionEvaluator {

    public boolean evaluate(String expression, Map<String, Object> context) {
        String expr = normalize(expression);
        RuleExprLexer lexer = new RuleExprLexer(CharStreams.fromString(expr));
        RuleExprParser parser = new RuleExprParser(new CommonTokenStream(lexer));
        RuleExprParser.ExprContext tree = parser.expr();
        return new EvalVisitor(context).visit(tree);
    }

    private String normalize(String expression) {
        return expression == null ? "" : expression.toUpperCase(Locale.ROOT).trim();
    }

    private static class EvalVisitor extends RuleExprBaseVisitor<Boolean> {
        private final Map<String, Object> context;

        private EvalVisitor(Map<String, Object> context) {
            this.context = context;
        }

        @Override
        public Boolean visitOrExpr(RuleExprParser.OrExprContext ctx) {
            return visit(ctx.expr(0)) || visit(ctx.expr(1));
        }

        @Override
        public Boolean visitAndExpr(RuleExprParser.AndExprContext ctx) {
            return visit(ctx.expr(0)) && visit(ctx.expr(1));
        }

        @Override
        public Boolean visitNotExpr(RuleExprParser.NotExprContext ctx) {
            return !visit(ctx.expr());
        }

        @Override
        public Boolean visitGroupExpr(RuleExprParser.GroupExprContext ctx) {
            return visit(ctx.expr());
        }

        @Override
        public Boolean visitConditionExpr(RuleExprParser.ConditionExprContext ctx) {
            return visit(ctx.condition());
        }

        @Override
        public Boolean visitInCondition(RuleExprParser.InConditionContext ctx) {
            String key = ctx.IDENT().getText();
            String value = String.valueOf(context.getOrDefault(key, ""));
            List<String> allowed = ctx.identList().IDENT().stream().map(t -> t.getText()).toList();
            return allowed.contains(value.toUpperCase(Locale.ROOT));
        }

        @Override
        public Boolean visitGtCondition(RuleExprParser.GtConditionContext ctx) {
            String key = ctx.IDENT().getText();
            double v = toDouble(context.getOrDefault(key, 0D));
            double threshold = Double.parseDouble(ctx.NUMBER().getText());
            return v > threshold;
        }

        @Override
        public Boolean visitLtCondition(RuleExprParser.LtConditionContext ctx) {
            String key = ctx.IDENT().getText();
            double v = toDouble(context.getOrDefault(key, 0D));
            double threshold = Double.parseDouble(ctx.NUMBER().getText());
            return v < threshold;
        }

        @Override
        public Boolean visitEqIdentCondition(RuleExprParser.EqIdentConditionContext ctx) {
            String key = ctx.IDENT(0).getText();
            String expected = ctx.IDENT(1).getText();
            return expected.equalsIgnoreCase(String.valueOf(context.getOrDefault(key, "")));
        }

        @Override
        public Boolean visitFlagCondition(RuleExprParser.FlagConditionContext ctx) {
            String key = ctx.IDENT().getText();
            Object value = context.getOrDefault(key, false);
            if (value instanceof Boolean b) {
                return b;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }

        private double toDouble(Object value) {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (Exception ignored) {
                return 0D;
            }
        }
    }
}
