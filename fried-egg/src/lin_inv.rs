use clap::Parser;
use egg::*;
use serde::*;
// use statement::Stmt;
use primitive_types::U256;
use rust_evm::{eval_evm, EVM};
use std::{cmp::*, collections::HashMap};
use symbolic_expressions::parser::parse_str;
use symbolic_expressions::Sexp;

pub type EGraph = egg::EGraph<EVM, TacAnalysis>;

// NOTE: this should be "freshness" perhaps. Oldest vars have least age.
//
#[derive(Parser)]
#[clap(rename_all = "kebab-case")]
pub enum Command {
    // only one command for now
    Optimize(OptParams),
}

#[derive(Serialize, Deserialize, Parser)]
#[clap(rename_all = "kebab-case")]
pub struct OptParams {
    ////////////////
    // eqsat args //
    ////////////////
    #[clap(long, default_value = "5")]
    pub eqsat_iter_limit: usize,
    #[clap(long, default_value = "100000")]
    pub eqsat_node_limit: usize,
    ////////////////
    // block from TAC CFG //
    ////////////////
    // #[clap(long, default_value = "input.json")]
    // pub input: String,
}

impl Default for OptParams {
    fn default() -> Self {
        Self {
            eqsat_iter_limit: 5,
            eqsat_node_limit: 10000,
        }
    }
}

pub struct EggAssign {
    pub lhs: String,
    pub rhs: Option<String>,
}
impl EggAssign {
    #[cfg(test)]
    fn new(lhs: impl AsRef<str>, rhs: impl AsRef<str>) -> Self {
        Self {
            lhs: lhs.as_ref().to_string(),
            rhs: Some(rhs.as_ref().to_string()),
        }
    }
}

pub struct LHSCostFn;
impl egg::CostFunction<EVM> for LHSCostFn {
    type Cost = usize;
    fn cost<C>(&mut self, enode: &EVM, mut costs: C) -> Self::Cost
    where
        C: FnMut(Id) -> Self::Cost,
    {
        let op_cost = match enode {
            EVM::Var(_) => 1,
            _ => 100,
        };
        enode.fold(op_cost, |sum, i| sum + costs(i))
    }
}

pub struct RHSCostFn<'a> {
    age_map: &'a HashMap<Symbol, usize>,
    age_limit: usize,
    lhs: Symbol,
}

impl egg::CostFunction<EVM> for RHSCostFn<'_> {
    type Cost = usize;
    fn cost<C>(&mut self, enode: &EVM, mut costs: C) -> Self::Cost
    where
        C: FnMut(Id) -> Self::Cost,
    {
        let op_cost = match enode {
            EVM::Num(_) => 1,
            EVM::Var(v) => {
                if v == &self.lhs {
                    1000
                } else if self.age_map.get(v).unwrap() < &self.age_limit {
                    10
                } else {
                    100
                }
            }
            _ => 5,
        };
        enode.fold(op_cost, |sum, i| sum + costs(i))
    }
}

#[derive(Default, Debug, Clone)]
pub struct Data {
    constant: Option<(U256, PatternAst<EVM>)>,
    age: Option<usize>,
}

#[derive(Debug, Clone)]
pub struct TacAnalysis {
    pub age_map: HashMap<Symbol, usize>,
}

impl Analysis<EVM> for TacAnalysis {
    type Data = Data;

    fn make(egraph: &egg::EGraph<EVM, TacAnalysis>, enode: &EVM) -> Self::Data {
        let ag = |i: &Id| egraph[*i].data.age;
        let age: Option<usize> = match enode {
            EVM::Num(_c) => Some(0),
            EVM::Add([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Sub([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Mul([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Div([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Lt([a, b]) => {
                //constant = None; // TODO: should change this to fold bools too
                match (ag(a), ag(b)) {
                    (Some(x), Some(y)) => Some(max(x, y)),
                    (_, _) => None,
                }
            }
            EVM::Gt([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Le([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Ge([a, b]) => match (ag(a), ag(b)) {
                (Some(x), Some(y)) => Some(max(x, y)),
                (_, _) => None,
            },
            EVM::Var(v) => {
                if let Some(age) = egraph.analysis.age_map.get(v) {
                    Some(*age)
                } else {
                    panic!("Cound not find age for variable {}", v);
                }
            }
            _ => None,
        };

        let mut child_const = vec![];
        enode.for_each(|child| child_const.push(egraph[child].data.constant.as_ref().map(|x| x.0)));
        let first = child_const.get(0).unwrap_or(&None);
        let second = child_const.get(1).unwrap_or(&None);
        let constant_option = eval_evm(enode, *first, *second);
        let constant = if let Some(c) = constant_option {
            let mut expr = PatternAst::default();
            let top_node = enode.clone().map_children(|child| {
                expr.add(ENodeOrVar::ENode(EVM::new(
                    egraph[child].data.constant.as_ref().unwrap().0,
                )))
            });
            expr.add(ENodeOrVar::ENode(top_node));
            Some((c, expr))
        } else {
            None
        };

        Data { constant, age }
    }

    fn merge(&mut self, to: &mut Self::Data, from: Self::Data) -> DidMerge {
        let mut merge_a = false;
        match (to.constant.as_ref(), from.constant) {
            (None, Some(b)) => {
                to.constant = Some(b);
                merge_a = true;
            }
            (None, None) => (),
            (Some(_), None) => (),
            (Some(a), Some(b)) => assert_eq!(a.0, b.0),
        }
        match (to.age, from.age) {
            (None, Some(b)) => {
                to.age = Some(b);
                merge_a = true;
            }
            (None, None) => (),
            (Some(_), None) => (),
            // when two eclasses with different variables are merged,
            // update the age to be the one of the youngest (largest age value).
            (Some(a), Some(b)) => to.age = Some(max(a, b)),
        }
        DidMerge(merge_a, true)
    }

    // We don't modify the eclass based on variable age.
    // Just add the constants we get from constant folding.
    fn modify(egraph: &mut EGraph, id: Id) {
        if let Some((c, lhs)) = egraph[id].data.constant.clone() {
            let mut const_pattern = PatternAst::default();
            const_pattern.add(ENodeOrVar::ENode(EVM::new(c)));
            let (id, _added) = egraph.union_instantiations(
                &lhs,
                &const_pattern,
                &Default::default(),
                "constant_folding",
            );

            assert!(
                !egraph[id].nodes.is_empty(),
                "empty eclass! {:#?}",
                egraph[id]
            );
        }
    }
}

// some standard axioms
pub fn rules() -> Vec<Rewrite<EVM, TacAnalysis>> {
    let mut uni_dirs: Vec<Rewrite<EVM, TacAnalysis>> = vec![
        rewrite!("commute-add"; "(+ ?a ?b)" => "(+ ?b ?a)"),
        rewrite!("commute-mul"; "(* ?a ?b)" => "(* ?b ?a)"),
        rewrite!("sub-cancel"; "(- ?a ?a)" => "0"),
        rewrite!("add-neg"; "(+ ?a (- 0 ?a))" => "0"),
        rewrite!("mul-0"; "(* ?a 0)" => "0"),
    ];

    let mut bi_dirs: Vec<Rewrite<EVM, TacAnalysis>> = vec![
        rewrite!("add-0"; "(+ ?a 0)" <=> "?a"),
        rewrite!("sub-0"; "(- ?a 0)" <=> "?a"),
        rewrite!("mul-1"; "(* ?a 1)" <=> "?a"),
        rewrite!("sub-add"; "(- ?a ?b)" <=> "(+ ?a (- 0 ?b))"),
        rewrite!("add-sub";  "(+ ?a (- 0 ?b))" <=> "(- ?a ?b)"),
        // rewrite!("assoc-add"; "(+ ?a (+ ?b ?c))" <=> "(+ (+ ?a ?b) ?c)"),
    ]
    .concat();

    uni_dirs.append(&mut bi_dirs);
    uni_dirs
}

// Get the eclass ids for all eclasses in an egraph
fn _ids(egraph: &EGraph) -> Vec<egg::Id> {
    egraph.classes().map(|c| c.id).collect()
}

pub struct TacOptimizer {
    params: OptParams,
    egraph: EGraph,
}

impl TacOptimizer {
    pub fn new(params: OptParams) -> Self {
        let analysis = TacAnalysis {
            age_map: Default::default(),
        };
        Self {
            params,
            egraph: EGraph::new(analysis).with_explanations_enabled(),
        }
    }

    pub fn run(mut self, block_assgns: Vec<EggAssign>) -> Vec<EggAssign> {
        for (index, assign) in block_assgns.iter().enumerate() {
            self.egraph
                .analysis
                .age_map
                .insert(egg::Symbol::from(assign.lhs.clone()), index + 1);
        }

        let mut roots = vec![];
        let mut res = vec![];
        // add lhs and rhs of each assignment to a new egraph
        // and union their eclasses
        for assign in &block_assgns {
            if let Some(rhs) = &assign.rhs {
                let id_l = self.egraph.add_expr(&assign.lhs.parse().unwrap());
                assert!(!rhs.is_empty(), "RHS of this assignment is empty!");
                let rhs_parsed: PatternAst<EVM> = rhs.parse().unwrap();
                // unbound variables have age 0
                for node in rhs_parsed.as_ref() {
                    if let ENodeOrVar::ENode(EVM::Var(name)) = node {
                        if self.egraph.analysis.age_map.get(name).is_none() {
                            self.egraph.analysis.age_map.insert(*name, 0);
                        }
                    }
                }

                self.egraph.union_instantiations(
                    &assign.lhs.parse().unwrap(),
                    &rhs_parsed,
                    &Default::default(),
                    "assignment",
                );
                roots.push(id_l);
            } else {
                roots.push(self.egraph.add_expr(&assign.lhs.parse().unwrap()));
            }
        }
        log::info!("Done adding terms to the egraph.");
        self.egraph.rebuild();

        // run eqsat with the domain rules
        let mut runner: Runner<EVM, TacAnalysis> = Runner::new(self.egraph.analysis.clone())
            .with_egraph(self.egraph)
            .with_iter_limit(self.params.eqsat_iter_limit)
            .with_node_limit(self.params.eqsat_node_limit)
            .with_scheduler(egg::SimpleScheduler);
        runner.roots = roots.clone();
        runner = runner.run(&rules());
        runner.egraph.rebuild();
        log::info!("Done running rules.");
        //runner.egraph.dot().to_svg("target/foo.svg").unwrap();
        for (c, id) in roots.into_iter().enumerate() {
            // TODO: carefully think why we know that the RHS corresponds to this LHS?
            // I think the root ids have the right order but need to be careful.
            let best_l: &RecExpr<EVM> = &block_assgns[c].lhs.parse().unwrap();
            // let extract_left = Extractor::new(&runner.egraph, LHSCostFn);
            // let best_l = extract_left.find_best(id).1;
            // check that this is indeed a var.
            if let EVM::Var(vl) = best_l.as_ref()[0] {
                let vl_age = *runner.egraph.analysis.age_map.get(&vl).unwrap();
                let extract_right = Extractor::new(
                    &runner.egraph,
                    RHSCostFn {
                        age_map: &runner.egraph.analysis.age_map,
                        age_limit: vl_age,
                        lhs: vl,
                    },
                );
                let best_r = extract_right.find_best(id).1.to_string();
                let assg = EggAssign {
                    lhs: best_l.to_string(),
                    rhs: if best_r == best_l.to_string() {
                        None
                    } else {
                        Some(best_r.to_string())
                    },
                };
                res.push(assg);
            }
        }
        res
    }
}

fn start(ss: Vec<EggAssign>) -> Vec<EggAssign> {
    let params: OptParams = OptParams::default();
    TacOptimizer::new(params).run(ss)
}

// Entry point
pub fn start_optimize(assignments: &Sexp) -> String {
    let mut ss: Vec<EggAssign> = vec![];

    if let Sexp::List(ref list) = assignments {
        for pair in list {
            if let Sexp::List(ref pair_list) = pair {
                let option_pair = (pair_list.get(0), pair_list.get(1));
                if let (Some(Sexp::String(lhs)), Some(rhs)) = option_pair {
                    ss.push(EggAssign {
                        lhs: lhs.clone(),
                        rhs: Some(rhs.to_string()),
                    });
                } else if let (Some(Sexp::String(lhs)), None) = option_pair {
                    ss.push(EggAssign {
                        lhs: lhs.clone(),
                        rhs: None,
                    });
                } else {
                    panic!("Invalid assignment pair: {:?}", pair_list);
                }
            } else {
                panic!("Expected a list of pairs!");
            }
        }
    } else {
        panic!("Expected a list of assignments!");
    }

    let mut res = vec![];
    for assignment in start(ss) {
        if let Some(right) = assignment.rhs {
            let right = parse_str(&right).unwrap();
            res.push(Sexp::List(vec![Sexp::String(assignment.lhs), right]));
        }
    }

    Sexp::List(res).to_string()

    // match Command::parse() {
    //     Command::Optimize(params) => {
    //         let opt = TacOptimizer::new(params);
    //         opt.run()

    //     }
    // }
}

#[cfg(test)]
mod tests {
    use super::*;
    use egg::{RecExpr, Symbol};
    use primitive_types::U256;
    use rust_evm::{WrappedU256, EVM};

    fn check_test(input: Vec<EggAssign>, expected: Vec<EggAssign>) {
        let _ = env_logger::builder().try_init();
        let actual = start(input);
        assert_eq!(actual.len(), expected.len());
        for (a, e) in actual.iter().zip(expected.iter()) {
            assert_eq!(a.lhs.to_string(), e.lhs.to_string());
            assert_eq!(a.rhs, e.rhs);
        }
    }

    #[test]
    fn test1() {
        let input = vec![
            EggAssign::new("R194", "64"),
            EggAssign::new("R198", "(+ 32 R194)"),
            EggAssign::new("R202", "(- R198 R194)"),
        ];
        let expected = vec![
            EggAssign::new("R194", "64"),
            EggAssign::new("R198", "96"),
            EggAssign::new("R202", "32"),
        ];
        check_test(input, expected);
    }

    #[test]
    // Should panic due to a missing feature.
    // We wanted to figure out a cost function that would allow us to extract the “right” RHS
    #[should_panic]
    fn test2() {
        let input = vec![
            EggAssign {
                lhs: "x2".to_string(),
                rhs: None,
            },
            EggAssign::new("x1", "(+ x2 96)"),
            EggAssign::new("x3", "(- x1 32)"),
            EggAssign::new("x4", "(- x3 x2)"),
        ];
        let expected = vec![
            EggAssign {
                lhs: "x2".to_string(),
                rhs: None,
            },
            EggAssign::new("x1", "(+ x2 96)"),
            EggAssign::new("x3", "(+ x2 64)"),
            EggAssign::new("x4", "64"),
        ];
        check_test(input, expected);
    }

    #[test]
    fn test4() {
        let input = vec![
            EggAssign::new("R1", "64"),
            EggAssign::new("R2", "(+ 32 R1)"),
        ];
        let expected = vec![EggAssign::new("R1", "64"), EggAssign::new("R2", "96")];
        check_test(input, expected);
    }

    #[test]
    fn test5() {
        let input = vec![
            EggAssign::new("R1", "64"),
            EggAssign::new("R2", "(- 32 R1)"),
        ];
        let expected =
            vec![
            EggAssign::new("R1", "64"),
            EggAssign::new("R2",
                "115792089237316195423570985008687907853269984665640564039457584007913129639904"),
        ];
        check_test(input, expected);
    }

    #[test]
    fn test6() {
        let input = vec![
            EggAssign::new("R1", "64"),
            EggAssign::new("R2", "(- R1 32)"),
        ];
        let expected = vec![EggAssign::new("R1", "64"), EggAssign::new("R2", "32")];
        check_test(input, expected);
    }
    #[test]
    fn test7() {
        let input = vec![EggAssign::new("R1", "(- 5 0)")];

        let expected = vec![EggAssign::new("R1", "5")];
        check_test(input, expected);
    }

    #[test]
    fn parse_test1() {
        let from_string: RecExpr<EVM> = "(+ x 0)".to_string().parse().unwrap();
        let v1 = EVM::Var(Symbol::from("x"));
        let v2 = EVM::Num(WrappedU256 {
            value: U256::zero(),
        });
        let mut foo = RecExpr::default();
        let id1 = foo.add(v1);
        let id2 = foo.add(v2);
        let _id3 = foo.add(EVM::Add([id1, id2]));
        assert_eq!(foo, from_string);
    }

    #[test]
    fn parse_test2() {
        let v1 = EVM::from(U256::from(32));
        let v2 = EVM::new(U256::from_dec_str("32").unwrap());
        assert_eq!(v1, v2);
    }
}

// TODO: need to havocify, then ssa-ify, then eqsat, then unhavoc-ify, then unssa-ify, handle other expressions
/*
x2 := havoc
x1 := x2 + 96 // x1 = x2 + 96
x3 := x1 - 32 // x3 = x2 + 64
x4 := x3 - x2 // x4 = 64
*/

/*
stuff to investigate
R2304 = tacM0x40
R2307 = (+ tacM0x40 32)
R2310 = (+ tacM0x40 64)
R2320 = (+ tacM0x40 96)
R2322 = (+ tacM0x40 128)
R2328 = (+ tacM0x40 160)
R2330 = (+ tacM0x40 192)
R2335 = tacM0x40
R2349 = (+ tacM0x40 224)
tacM0x40 = R2349
R2361 = (+ tacM0x40 256)
R2395 = (+ tacM0x40 322)
tacM0x40 = R2395
R2560 = tacM0x40
R2564 = (+ 32 R2560)
tacM0x40.1375 = R2564
R2565 = tacM0x40.1375
R2570 = (+ 32 R2565)
R2574 = (& 255 R1284)
R2577 = (+ 32 R2570)
R2581 = (+ 32 R2577)
R2585 = (+ 32 R2581)
R2588 = 32
R2589 = tacM0x40.1375
R2591 = (- R2589 32)
R2598 = (- R2585 R2589)
R2603 = 1
R2605 = tacRC
B2608 = (== R2605 0)
B2614 = (! B2608)
*/
