use cvlr::log::CvlrLog;
use cvlr::nondet::Nondet;
use cvlr::{log::cvlr_log_with};
use cvlr::{prelude::*};

use crate::certora::spec::structs::{MiddleStruct};

#[rule]
pub fn rule_macro_property() {
    let created_struct: MiddleStruct = MiddleStruct::nondet();
    simple_property_in_macro::<BasicProperty>(&created_struct);
}

pub trait CvlrProp: CvlrLog {
    /// new
    fn new(input: &MiddleStruct) -> Self;
    /// assume
    fn assume_pre(&self);
    /// post condition
    fn check_post(&self, old: &Self);
}

#[inline(always)]
pub fn simple_property_in_macro<'info, P: CvlrProp>(created_struct: &MiddleStruct) {
    let pre_condition_method = P::new(created_struct);
    pre_condition_method.assume_pre();

    clog!(pre_condition_method);

    let post_condition_method = P::new(created_struct);
    post_condition_method.check_post(&pre_condition_method);
    clog!(post_condition_method);
}

macro_rules! create_property {
    ($name:ident, $self_ident:ident, $old_ident:ident, pre_block: $pre_block:block, post_block: $post_block:block) => {
        #[derive(Clone)]
        pub struct $name {
            pub field2: u64,
            pub field3: u64,
        }

        impl cvlr::log::CvlrLog for $name {
            fn log(&self, my_tag: &str, my_logger: &mut cvlr::log::CvlrLogger) {
                my_logger.log_scope_start(my_tag);
                cvlr_log_with("field2", &self.field2, my_logger);
                cvlr_log_with("field3", &self.field3, my_logger);
                my_logger.log_scope_end(my_tag);
            }
        }

        impl CvlrProp for $name {
            fn new(input: &MiddleStruct) -> Self {
                Self{
                    field2: input.field2,
                    field3: input.field3
                }
            }
            fn assume_pre(&$self_ident) {
                $pre_block
            }

            /// post condition
            fn check_post(&$self_ident, $old_ident: &Self) {
                $post_block
            }
        }
    };
}

create_property!(BasicProperty, self, _old, pre_block: {},
    post_block: {
        // Will not fail
        cvlr_assert!(self.field2 == 7);
        // Will fail
        cvlr_assert!(self.field3 == 1);
    }
);