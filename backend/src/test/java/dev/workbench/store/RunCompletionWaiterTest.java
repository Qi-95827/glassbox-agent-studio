package dev.workbench.store;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 多消费者共享同一运行通知；一个消费者退出不影响其他消费者。 */
class RunCompletionWaiterTest {
  @Test
  void oneConsumerLeavingDoesNotDropOtherWaiters() {
    var waiter = new RunCompletionWaiter();
    var first = waiter.register("run");
    var second = waiter.register("run");
    waiter.remove("run", first);
    waiter.signal("run");
    assertFalse(first.isDone());
    assertTrue(second.isDone());
  }

  @Test
  void signalAfterRegisterCompletesAndTimeoutIsBounded() throws Exception {
    var waiter = new RunCompletionWaiter();
    var future = waiter.register("run");
    waiter.signal("run");
    assertTrue(waiter.await(future, 1));
    var late = waiter.register("late");
    assertFalse(waiter.await(late, 5));
    waiter.signal("late");
    assertTrue(late.isDone());
  }
}
