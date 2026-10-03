# Observability

Since `1.4.0`, SafeBox failures can be observed with a `FailureListener`:

```kotlin
val prefs = SafeBox.create(
    context = context,
    fileName = "preferences",
    failureListener = SafeBox.FailureListener { failure ->
        Timber.e(failure.error, "%s", failure)
    },
)
```

A failure contains the original exception, the SafeBox file name, and the operation during
which it occurred. Operations are `INITIAL_LOAD`, `READ`, `WRITE`, and `RECOVERY_REPLAY`.
SafeBox adds no preference key names or stored values to the event.
The original exception is passed unchanged, so its message and stack trace may contain
application- or provider-supplied details.

Callbacks are delivered sequentially on `Dispatchers.IO` in the order failures are accepted
by an instance. Keep callbacks short. The listener is immutable after creation. Repeated
`create()` calls for the same file keep the original listener.

SafeBox does not additionally log failures successfully delivered to the listener. If no
listener is configured, the failure is written to logcat. If the listener queue is full,
or the listener itself throws, SafeBox falls back to logcat.

`CancellationException` is not reported. Unreadable-record cleanup sends no notifications.

Callbacks may begin before `create()` returns. Exceptions preventing construction still
propagate to the caller. Observability does not change `commit()` results, recovery behavior,
or exceptions propagated by SafeBox calls.
