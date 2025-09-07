# Observing State Changes

You can observe SafeBox lifecycle state transitions (`STARTING`, `WRITING`, `IDLE`) in two ways.

## 1. Instance-bound listener

```kotlin
val safeBox = SafeBox.create(
    context = context,
    fileName = PREF_FILE_NAME,
    stateListener = SafeBoxStateListener { state ->
        when (state) {
            SafeBoxState.STARTING -> trackStart()    // Loading from disk
            SafeBoxState.IDLE     -> trackIdle()     // No active persistence
            SafeBoxState.WRITING  -> trackWrite()    // Persisting to disk
        }
    }
)
```

## 2. Global observer

```kotlin
val listener = SafeBoxStateListener { state ->
    when (state) {
        SafeBoxState.STARTING -> onStart()
        SafeBoxState.IDLE     -> onIdle()
        SafeBoxState.WRITING  -> onWrite()
    }
}
SafeBoxGlobalStateObserver.addListener(PREF_FILE_NAME, listener)

// later
SafeBoxGlobalStateObserver.removeListener(PREF_FILE_NAME, listener)
```

You can also query the current state:

```kotlin
val state = SafeBoxGlobalStateObserver.getCurrentState(PREF_FILE_NAME)
```