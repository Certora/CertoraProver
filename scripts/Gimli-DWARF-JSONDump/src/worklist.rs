use std::collections::{HashSet, VecDeque};
use std::hash::Hash;

/// A generic worklist algorithm implementation
pub struct Worklist<T> {
    queue: VecDeque<T>,
    seen: HashSet<T>,
}

impl<T> Worklist<T>
where
    T: Eq + Hash + Clone,
{
    /// Creates a new empty worklist
    pub fn new() -> Self {
        Worklist {
            queue: VecDeque::new(),
            seen: HashSet::new(),
        }
    }

    /// Adds an item to the worklist if it hasn't been seen before
    pub fn add(&mut self, item: T) -> bool {
        if self.seen.insert(item.clone()) {
            self.queue.push_back(item);
            true
        } else {
            false
        }
    }

    /// Adds multiple items to the worklist
    pub fn add_all(&mut self, items: impl IntoIterator<Item = T>) {
        for item in items {
            self.add(item);
        }
    }

    /// Removes and returns the next item from the worklist
    pub fn pop(&mut self) -> Option<T> {
        self.queue.pop_front()
    }
}
