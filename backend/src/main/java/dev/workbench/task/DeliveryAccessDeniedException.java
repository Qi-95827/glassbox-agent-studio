package dev.workbench.task;

/** 当前授权已撤销交付文件时拒绝读取正文；历史回执仍保留在交付投影中。 */
public class DeliveryAccessDeniedException extends RuntimeException {
  public DeliveryAccessDeniedException(String message) {
    super(message);
  }
}
