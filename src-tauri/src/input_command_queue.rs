use std::future::Future;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
#[cfg(test)]
use tokio::sync::oneshot;
use tokio::sync::{mpsc, Notify};

enum QueueItem<Command> {
    Command {
        generation: u64,
        command: Command,
    },
    #[cfg(test)]
    Barrier(oneshot::Sender<()>),
}

struct QueueState {
    generation: AtomicU64,
    stopped: AtomicBool,
    submission: Mutex<()>,
    shutdown: Notify,
}

pub(crate) struct InputCommandQueue<Command> {
    sender: mpsc::Sender<QueueItem<Command>>,
    state: Arc<QueueState>,
}

impl<Command: Send + 'static> InputCommandQueue<Command> {
    pub(crate) fn new<Handler, Work>(capacity: usize, mut handler: Handler) -> Self
    where
        Handler: FnMut(Command) -> Work + Send + 'static,
        Work: Future<Output = ()> + Send + 'static,
    {
        let (sender, mut receiver) = mpsc::channel(capacity);
        let state = Arc::new(QueueState {
            generation: AtomicU64::new(1),
            stopped: AtomicBool::new(false),
            submission: Mutex::new(()),
            shutdown: Notify::new(),
        });
        let worker_state = state.clone();
        tokio::spawn(async move {
            loop {
                if worker_state.stopped.load(Ordering::SeqCst) {
                    receiver.close();
                }
                let item = if worker_state.stopped.load(Ordering::SeqCst) {
                    receiver.recv().await
                } else {
                    tokio::select! {
                        biased;
                        _ = worker_state.shutdown.notified() => continue,
                        item = receiver.recv() => item,
                    }
                };
                let Some(item) = item else {
                    break;
                };
                match item {
                    QueueItem::Command {
                        generation,
                        command,
                    } => {
                        if !worker_state.stopped.load(Ordering::SeqCst)
                            && worker_state.generation.load(Ordering::SeqCst) == generation
                        {
                            handler(command).await;
                        }
                    }
                    #[cfg(test)]
                    QueueItem::Barrier(completion) => {
                        let _ = completion.send(());
                    }
                }
            }
        });
        Self { sender, state }
    }

    pub(crate) fn submit(&self, command: Command) -> Result<(), &'static str> {
        let _guard = self
            .state
            .submission
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if self.state.stopped.load(Ordering::SeqCst) {
            return Err("Input command queue is stopped");
        }
        self.sender
            .try_send(QueueItem::Command {
                generation: self.state.generation.load(Ordering::SeqCst),
                command,
            })
            .map_err(|_| "Input command queue is unavailable or full")
    }

    pub(crate) fn invalidate(&self) {
        let _guard = self
            .state
            .submission
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        self.state.generation.fetch_add(1, Ordering::SeqCst);
    }

    pub(crate) fn shutdown(&self) {
        let _guard = self
            .state
            .submission
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        self.state.stopped.store(true, Ordering::SeqCst);
        self.state.generation.fetch_add(1, Ordering::SeqCst);
        self.state.shutdown.notify_one();
    }

    #[cfg(test)]
    pub(crate) async fn flush(&self) -> Result<(), &'static str> {
        let (sender, receiver) = oneshot::channel();
        self.sender
            .send(QueueItem::Barrier(sender))
            .await
            .map_err(|_| "Input command queue is closed")?;
        receiver
            .await
            .map_err(|_| "Input command queue barrier was dropped")
    }
}

#[cfg(test)]
mod tests {
    use super::InputCommandQueue;
    use std::sync::{Arc, Mutex};

    #[tokio::test]
    async fn barrier_observes_prior_commands_and_shutdown_rejects_later_commands() {
        let received = Arc::new(Mutex::new(Vec::new()));
        let worker_received = received.clone();
        let queue = InputCommandQueue::new(4, move |command: u64| {
            let received = worker_received.clone();
            async move {
                received.lock().unwrap().push(command);
            }
        });
        queue.submit(7).unwrap();
        queue.submit(8).unwrap();
        queue.flush().await.unwrap();
        assert_eq!(*received.lock().unwrap(), vec![7, 8]);
        queue.shutdown();
        assert!(queue.submit(9).is_err());
    }
}
