package local.glasstv.launcher;

/** Keeps an activity callback only for its bound lifetime. */
final class ReceiverAttachment<T> {
  private volatile T current;

  void attach(T value) { current = value; }

  void detach(T value) {
    if (current == value) current = null;
  }

  T current() { return current; }
}
