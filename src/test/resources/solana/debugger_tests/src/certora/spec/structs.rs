use cvlr::{nondet::Nondet, prelude::*};
use cvlr::log::cvlr_log_with;
use cvlr::log::CvlrLog;
pub struct Foo {
    pub key: u64,
    pub bar: Bar,
}

pub struct Bar {
    pub owner: u64,
}
#[derive(Debug, Clone)]
pub struct LargeStruct{
    pub a: u64,
    pub b: u64,
    pub c: u64,
    pub d: u64,
    pub e: u64,
    pub f: u64,
    pub g: u64,
    pub h: u64,
}

impl Nondet for Bar {
    fn nondet() -> Self {
        Bar { owner: nondet() }
    }
}
impl Nondet for Foo {
    fn nondet() -> Self {
        Foo {
            key: nondet(),
            bar: Bar::nondet(),
        }
    }
}

impl Nondet for LargeStruct{
    fn nondet() -> Self {
        LargeStruct {
            a: 9, b: nondet(), c: nondet(), d: nondet(), e: 8, f: nondet(), g: 7, h: nondet() }
    }
}


#[derive(Debug, Clone)]
pub struct MiddleStruct{
    pub field1: u64,
    pub field2: u64,
    pub field3: u64,
}

impl Nondet for MiddleStruct{
    fn nondet() -> Self {
        MiddleStruct {
            field1: 9, field2: 7, field3: nondet()}
    }
}


#[derive(PartialEq)]
pub struct ParentStruct<'info>{
    pub substruct: &'info SubStruct,
    pub fixed_value_2: u8,
}

#[derive(PartialEq)]
pub struct SubStruct{
    pub fixed_value_1: u8,
    pub nondet_value: u8
}

impl CvlrLog for SubStruct {
    fn log(&self, tag: &str, logger: &mut cvlr::log::CvlrLogger) {
        logger.log_scope_start(tag);
        cvlr_log_with("\tfixed_value_1", &self.fixed_value_1, logger);
        cvlr_log_with("\tnondet_value", &self.nondet_value, logger);
        logger.log_scope_end(tag);
    }
}

impl<'info> CvlrLog for ParentStruct<'info> {
    fn log(&self, tag: &str, logger: &mut cvlr::log::CvlrLogger) {
        logger.log_scope_start(tag);
        cvlr_log_with("\tsubstruct", &self.substruct, logger);
        cvlr_log_with("\tfixed_value_2", &self.fixed_value_2, logger);
        logger.log_scope_end(tag);
    }
}