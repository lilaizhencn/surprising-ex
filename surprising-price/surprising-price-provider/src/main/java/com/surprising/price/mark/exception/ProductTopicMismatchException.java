package com.surprising.price.mark.exception;

/** 当前产品不接受其他产品的价格流。 */
public final class ProductTopicMismatchException extends RuntimeException {
    public ProductTopicMismatchException(String message) { super(message); }
}
