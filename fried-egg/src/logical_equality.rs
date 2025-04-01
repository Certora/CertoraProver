use egg::{rewrite, Analysis, DidMerge, Id, Language, Pattern, RecExpr, Rewrite, Runner};
use primitive_types::U256;
use rand::seq::SliceRandom;
use rand::{thread_rng, Rng};
use rust_evm::{eval_evm, EVM};
use std::time::Duration;

use serde_json::Value;

pub fn get_pregenerated_rules() -> Vec<(String, String)> {
    let contents = include_str!("./ruler-rules.json");
    let json = serde_json::from_str(contents).unwrap();
    let rules = match &json {
        Value::Object(map) => {
            if let Some(Value::Array(rules)) = map.get("all_eqs") {
                rules
            } else {
                panic!("expected array from json all_eqs");
            }
        }
        _ => panic!("invalid json"),
    };
    rules
        .iter()
        .flat_map(|entry| {
            let get = || {
                let rule = entry.as_object()?;
                let lhs = rule.get("lhs")?.as_str()?.to_string();
                let rhs = rule.get("rhs")?.as_str()?.to_string();
                let bidirectional = rule.get("bidirectional")?.as_bool()?;
                Some((lhs, rhs, bidirectional))
            };
            let (lhs, rhs, bidirectional) =
                get().unwrap_or_else(|| panic!("invalid entry in rules {entry}", entry = entry));
            if bidirectional {
                vec![(lhs.clone(), rhs.clone()), (rhs, lhs)]
            } else {
                vec![(lhs, rhs)]
            }
        })
        .chain([(
            "(* ?a (+ ?b ?c))".to_string(),
            "(+ (* ?a ?b) (* ?a ?c))".to_string(),
        )])
        .collect()
}

pub fn start_logical_pair(expr1: String, expr2: String, timeout: u64) -> (bool, bool) {
    if expr1 == expr2 {
        return (true, true);
    }
    let expr1 = expr1.parse().unwrap();
    let expr2 = expr2.parse().unwrap();
    let mut runner = LogicalRunner::new();
    runner.add_pair(&expr1, &expr2);
    if runner.are_unequal_fuzzing(&expr1, &expr2) {
        (false, true)
    } else {
        (runner.run(timeout).are_equal(&expr1, &expr2), false)
    }
}

pub fn start_logical(expr1: String, expr2: String, timeout: u64) -> String {
    let res = start_logical_pair(expr1, expr2, timeout);
    format!("({} {})", res.0, res.1)
}

pub fn logical_rules() -> Vec<Rewrite<EVM, LogicalAnalysis>> {
    get_pregenerated_rules()
        .into_iter()
        .enumerate()
        .map(|(index, (lhs, rhs))| {
            let lparsed: Pattern<EVM> = lhs.parse().unwrap();
            let rparsed: Pattern<EVM> = rhs.parse().unwrap();

            Rewrite::<EVM, LogicalAnalysis>::new(index.to_string(), lparsed, rparsed).unwrap()
        })
        .chain([
            rewrite!("distr*+"; "(* (+ ?a ?b) ?c)" => "(+ (* ?a ?c) (* ?b ?c))"),
            rewrite!("doubleneg!=="; "(! (== (== ?x ?y) 0))" => "(== ?x ?y)"),
        ])
        .collect()
}

type EGraph = egg::EGraph<EVM, LogicalAnalysis>;

#[derive(Default, Debug, Clone)]
pub struct Data {
    cvec: Vec<U256>,
    constant: Option<U256>,
}

fn random_256() -> U256 {
    let mut rng = rand::thread_rng();
    let lower = U256::from_dec_str(&rng.gen::<u128>().to_string()).unwrap();
    let dummy_vec: [Id; 2] = [Id::from(0), Id::from(0)];
    let upper = eval_evm(
        &EVM::ShiftLeft(dummy_vec),
        Some(lower),
        Some(U256::from_dec_str("128").unwrap()),
    )
    .unwrap();
    let lower_2 = U256::from_dec_str(&rng.gen::<u128>().to_string()).unwrap();
    eval_evm(&EVM::BWOr(dummy_vec), Some(lower_2), Some(upper)).unwrap()
}

const CVEC_LEN: usize = 30;

#[derive(Default, Debug, Clone)]
pub struct LogicalAnalysis {
    special_constants: Vec<U256>,
    cvec_enabled: bool,
}
impl Analysis<EVM> for LogicalAnalysis {
    type Data = Data;

    fn make(egraph: &EGraph, enode: &EVM) -> Self::Data {
        // cvecs used for fuzzing in the egraph
        let cvec = if matches!(enode, EVM::Var(_)) && egraph.analysis.cvec_enabled {
            let mut cvec = egraph.analysis.special_constants.clone();
            // randomize order of constants
            cvec.shuffle(&mut thread_rng());
            cvec.extend(egraph.analysis.special_constants.clone());
            cvec.extend((0..CVEC_LEN.saturating_sub(cvec.len())).map(|_| random_256()));
            cvec.truncate(CVEC_LEN);
            cvec
        } else if egraph.analysis.cvec_enabled {
            let get_evec = |child_index, cvec_index| {
                let &child = enode.children().get(child_index)?;
                egraph[child].data.cvec.get(cvec_index).copied()
            };
            (0..CVEC_LEN)
                .map(|cvec_index| (get_evec(0, cvec_index), get_evec(1, cvec_index)))
                .map(|(first, second)| {
                    eval_evm(enode, first, second).unwrap_or_else(|| {
                        panic!(
                            "eval_evm for {:?} failed, with children {:?} and {:?}",
                            enode, first, second
                        )
                    })
                })
                .collect()
        } else {
            vec![]
        };
        let get_constant = |index| {
            let &child = enode.children().get(index)?;
            egraph[child].data.constant
        };
        let constant = eval_evm(enode, get_constant(0), get_constant(1));

        Data { cvec, constant }
    }

    fn merge(&mut self, to: &mut Self::Data, from: Self::Data) -> DidMerge {
        let mut merge_l = false;
        let mut merge_r = false;
        match (to.constant, from.constant) {
            (None, Some(b)) => {
                to.constant = Some(b);
                merge_l = true;
            }
            (None, None) => (),
            (Some(_), None) => {
                merge_r = true;
            }
            (Some(a), Some(b)) => assert_eq!(a, b),
        }

        DidMerge(merge_l, merge_r)
    }

    fn modify(egraph: &mut EGraph, id: Id) {
        let class = &mut egraph[id];
        if let Some(c) = class.data.constant {
            let added = egraph.add(EVM::from(c));
            egraph.union(id, added);
            assert!(
                !egraph[id].nodes.is_empty(),
                "empty eclass! {:#?}",
                egraph[id]
            );
        }
    }
}

#[derive(Debug)]
pub struct LogicalRunner {
    egraph: EGraph,
    fuzzing_egraph: EGraph,
    exprs: Vec<(RecExpr<EVM>, RecExpr<EVM>)>,
}

impl LogicalRunner {
    pub fn new() -> Self {
        let constants = vec![
            U256::zero(),
            U256::one(),
            U256::zero().overflowing_sub(U256::one()).0,
        ];
        let analysis = LogicalAnalysis {
            special_constants: constants,
            cvec_enabled: true,
        };
        LogicalRunner {
            egraph: EGraph::new(LogicalAnalysis::default()),
            fuzzing_egraph: EGraph::new(analysis),
            exprs: vec![],
        }
    }

    fn add_constants(egraph: &mut EGraph, expr: &RecExpr<EVM>) {
        for node in expr.as_ref() {
            if let EVM::Num(c) = node {
                egraph.analysis.special_constants.push(c.value);
                egraph
                    .analysis
                    .special_constants
                    .push(c.value.overflowing_add(U256::one()).0);
                egraph
                    .analysis
                    .special_constants
                    .push(c.value.overflowing_sub(U256::one()).0);
            }
        }
    }

    pub fn add_expr(&mut self, expr: &RecExpr<EVM>) -> &'_ mut Self {
        self.egraph.add_expr(expr);
        LogicalRunner::add_constants(&mut self.fuzzing_egraph, expr);
        self
    }

    pub fn add_pair(&mut self, expr1: &RecExpr<EVM>, expr2: &RecExpr<EVM>) -> &'_ mut Self {
        self.add_expr(expr1).add_expr(expr2);
        self.exprs.push((expr1.clone(), expr2.clone()));
        self
    }

    pub fn are_unequal_fuzzing(&mut self, lhs: &RecExpr<EVM>, rhs: &RecExpr<EVM>) -> bool {
        let start_f = self.fuzzing_egraph.add_expr(lhs);
        let end_f = self.fuzzing_egraph.add_expr(rhs);
        self.fuzzing_egraph.rebuild();
        let leftvec = &self.fuzzing_egraph[start_f].data.cvec;
        let rightvec = &self.fuzzing_egraph[end_f].data.cvec;
        leftvec != rightvec
    }

    pub fn are_equal(&mut self, lhs: &RecExpr<EVM>, rhs: &RecExpr<EVM>) -> bool {
        let start = self.egraph.add_expr(lhs);
        let end = self.egraph.add_expr(rhs);
        start == end
    }

    pub fn run(self, timeout: u64) -> Self {
        let exprs_check = self.exprs.clone();
        let runner: Runner<EVM, LogicalAnalysis> = Runner::new(LogicalAnalysis::default())
            .with_egraph(self.egraph)
            .with_node_limit(1_000_000)
            .with_time_limit(Duration::from_millis(timeout))
            .with_iter_limit(usize::MAX)
            .with_hook(move |runner| {
                let mut done = true;
                for (expr1, expr2) in &exprs_check {
                    if runner.egraph.add_expr(expr1) != runner.egraph.add_expr(expr2) {
                        done = false;
                        break;
                    }
                }
                if done {
                    Err("All equal".to_string())
                } else {
                    Ok(())
                }
            })
            .run(&logical_rules());

        Self {
            egraph: runner.egraph,
            ..self
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logical_proves_equal() {
        let queries = vec![
            ("(+ 1 1)", "2"),
            ("(- a 1)", "(+ a (- 0 1))"),
            ("(* (- c 1) 32)", "(- (* c 32) 32)"),
            ("(- (+ a (+ b (* c 32))) (+ a (+ b (* (- c 1) 32))))", "32"),
            (
                "(- (+ a (+ b (* longname 32))) (+ a (+ b (* (- longname 1) 32))))",
                "32",
            ),
            (
                "(! (== (== 3264763256 tacSighash) 0))",
                "(== 3264763256 tacSighash)",
            ),
            // This is a good example of one we can't solve right now
            // ("(== (! (== certoraOutVar0bv256 0)) 0)", "(== certoraOutVar0bv256 0)"),
        ];
        for (lhs, rhs) in queries {
            let res = start_logical_pair(lhs.to_string(), rhs.to_string(), 8000);
            if !res.0 {
                if res.1 {
                    panic!("Proved unequal: {} and {}", lhs, rhs,);
                }
                panic!("could not prove equal {},   {}", lhs, rhs);
            }
        }
    }
}
