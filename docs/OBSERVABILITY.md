# Observability

Since `1.4.0`, you can observe SafeBox failures:

```kotlin
val prefs = SafeBox.create(
    context = context,
    fileName = "preferences",
    failureListener = SafeBox.FailureListener { error, trace ->
        Timber.e(error, trace)
    },
)
```

Example output (`trace`):

```text
SafeBox "preferences" write: failed to flush primary storage
  batch: put name, remove session
```

## Behavior

- The listener is immutable after creation. Repeated `create()` calls for the same file keep the original listener.
- Omitting the listener or passing `null` retains existing failure messages in logcat. Confirmed unreadable-record removal also logs its count and triggering authentication exception.
- Callbacks run sequentially on `Dispatchers.IO`, in the order failures are accepted per instance, independently of the storage dispatcher. Keep callbacks short.
- SafeBox does not also log successfully delivered failures. If the queue fills, new failures go to logcat. If the listener throws, one fallback log includes the trace and both exceptions.
- Callbacks may begin before `create()` returns. Exceptions preventing creation still propagate to the caller.
- Reports identify the operation and the failing step, including whether decryption involved a key or value.
- Confirmed cleanup reports include the number of removed unreadable records. Their exception is one of the authentication failures that caused the cleanup. Detection alone does not confirm removal.
- Observation does not change `commit()` results or replace handling exceptions from SafeBox calls.

## Privacy

Traces include file and key names, but no stored values. Exceptions are passed unchanged. Review both before forwarding them externally.

Logcat fallbacks include these details without application-side redaction.
