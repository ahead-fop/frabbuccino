//
// frabbuccino model
//


// =============================================================================
//     Grammar
// =============================================================================

frabbuccino :  [tracematching] eaj* [advice_weaving] [abc] base :: Main ;

eaj :

    cast_pc                    |
    cflowdepth_pc              |
    contains_pc                |
    global_pc                  |
    let_pc                     |
    local_pc_vars              |
    throw_pc                   ;


// =============================================================================
%% //  Constraints
// =============================================================================

// abc
abc implies base ;

// advice_weaving
advice_weaving implies abc ;

// eaj
cast_pc implies advice_weaving ;
cflowdepth_pc implies advice_weaving ;
contains_pc implies advice_weaving ;
global_pc implies advice_weaving ;
let_pc implies advice_weaving ;
local_pc_vars implies advice_weaving ;
throw_pc implies advice_weaving ;

// tracematching
tracematching implies local_pc_vars ;


// =============================================================================
## //  Formatting
// =============================================================================

// abc
abc { disp = "ITDs plus declare parents" }

// advice_weaving
advice_weaving { disp = "advice weaving" }

// eaj
eaj { disp = "features from eaj" }
cast_pc { disp = "cast pointcut" }
cflowdepth_pc { disp = "cflowdepth pointcut" }
contains_pc { disp = "contains pointcut" }
global_pc { disp = "global pointcuts" }
let_pc { disp = "let pointcut" }
local_pc_vars { disp = "local pointcut variables" }
throw_pc { disp = "throw pointcut" }

// tracematching
tracematching { disp = "trace matching" }

