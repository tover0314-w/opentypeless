#[path = "../src/monitor_transaction.rs"]
mod monitor_transaction;

use std::cell::{Cell, RefCell};
use std::rc::Rc;

struct Monitor {
    name: &'static str,
    events: Rc<RefCell<Vec<&'static str>>>,
}

impl Drop for Monitor {
    fn drop(&mut self) {
        self.events.borrow_mut().push(self.name);
    }
}

#[test]
fn failed_candidate_preserves_previous_monitor_and_generation() {
    let events = Rc::new(RefCell::new(Vec::new()));
    let mut current = Some(Monitor {
        name: "old",
        events: Rc::clone(&events),
    });
    let committed = Cell::new(false);
    let result = monitor_transaction::replace_after_start(
        &mut current,
        || Err("startup failed"),
        || committed.set(true),
    );
    assert_eq!(result, Err("startup failed"));
    assert!(!committed.get());
    assert_eq!(current.as_ref().unwrap().name, "old");
    assert!(events.borrow().is_empty());
}

#[test]
fn commit_precedes_old_monitor_shutdown() {
    let events = Rc::new(RefCell::new(Vec::new()));
    let mut current = Some(Monitor {
        name: "old",
        events: Rc::clone(&events),
    });
    monitor_transaction::replace_after_start(
        &mut current,
        || {
            events.borrow_mut().push("start");
            Ok::<_, &str>(Monitor {
                name: "new",
                events: Rc::clone(&events),
            })
        },
        || events.borrow_mut().push("commit"),
    )
    .unwrap();
    assert_eq!(*events.borrow(), ["start", "commit", "old"]);
    assert_eq!(current.as_ref().unwrap().name, "new");
}
