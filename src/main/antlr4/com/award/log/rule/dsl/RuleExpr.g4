grammar RuleExpr;

expr
    : expr OR expr          # OrExpr
    | expr AND expr         # AndExpr
    | NOT expr              # NotExpr
    | LPAREN expr RPAREN    # GroupExpr
    | condition             # ConditionExpr
    ;

condition
    : IDENT IN LPAREN identList RPAREN        # InCondition
    | IDENT GT NUMBER                         # GtCondition
    | IDENT LT NUMBER                         # LtCondition
    | IDENT EQ IDENT                          # EqIdentCondition
    | IDENT                                   # FlagCondition
    ;

identList: IDENT (COMMA IDENT)*;

AND: 'AND';
OR: 'OR';
NOT: 'NOT';
IN: 'IN';
GT: '>';
LT: '<';
EQ: '=';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
IDENT: [A-Z_][A-Z0-9_\\.]*;
NUMBER: [0-9]+ ('.' [0-9]+)?;
WS: [ \t\r\n]+ -> skip;
