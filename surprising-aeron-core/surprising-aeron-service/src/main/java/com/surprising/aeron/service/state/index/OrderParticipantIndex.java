package com.surprising.aeron.service.state.index;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.TradingDependencyMask;
import com.surprising.aeron.service.state.model.CoreOrderState;

/** Owner-only price/participant reference counts, rebuilt from active orders after recovery.
 * Subtree masks allow a crossing-price range query without walking orders or price levels. */
final class OrderParticipantIndex {
    private Node bids, asks;

    long mask() { return mask(bids) | mask(asks); }

    long counterparties(CoreOrderSide incomingSide, long limitPrice) {
        Node node = incomingSide == CoreOrderSide.BUY ? asks : bids;
        if (limitPrice == 0) return mask(node); // Market admission may consume any opposing level.
        long result = 0;
        while (node != null) {
            boolean crosses = incomingSide == CoreOrderSide.BUY ? node.price <= limitPrice : node.price >= limitPrice;
            if (crosses) {
                result |= 1L << node.partition;
                result |= mask(incomingSide == CoreOrderSide.BUY ? node.left : node.right);
                node = incomingSide == CoreOrderSide.BUY ? node.right : node.left;
            } else node = incomingSide == CoreOrderSide.BUY ? node.left : node.right;
        }
        return result;
    }

    void add(CoreOrderState order) { change(order, 1); }
    void remove(CoreOrderState order) { change(order, -1); }

    private void change(CoreOrderState order, int delta) {
        int partition = TradingDependencyMask.partition(order.userId());
        if (order.side() == CoreOrderSide.BUY) bids = change(bids, order.matchingPriceTicks(), partition, delta);
        else asks = change(asks, order.matchingPriceTicks(), partition, delta);
    }

    private static Node change(Node node, long price, int partition, int delta) {
        if (node == null) {
            if (delta < 0) throw new IllegalStateException("active order participant count underflow");
            return new Node(price, partition);
        }
        int compared = Long.compare(price, node.price);
        if (compared == 0) compared = Integer.compare(partition, node.partition);
        if (compared < 0) node.left = change(node.left, price, partition, delta);
        else if (compared > 0) node.right = change(node.right, price, partition, delta);
        else {
            node.count = Math.addExact(node.count, delta);
            if (node.count == 0) {
                if (node.left == null) return node.right;
                if (node.right == null) return node.left;
                Node successor = node.right;
                while (successor.left != null) successor = successor.left;
                node.price = successor.price;
                node.partition = successor.partition;
                node.count = successor.count;
                node.right = removeFirst(node.right);
            }
        }
        return balance(node);
    }

    private static Node removeFirst(Node node) {
        if (node.left == null) return node.right;
        node.left = removeFirst(node.left);
        return balance(node);
    }

    private static Node balance(Node node) {
        refresh(node);
        int difference = height(node.left) - height(node.right);
        if (difference > 1) {
            if (height(node.left.left) < height(node.left.right)) node.left = rotateLeft(node.left);
            return rotateRight(node);
        }
        if (difference < -1) {
            if (height(node.right.right) < height(node.right.left)) node.right = rotateRight(node.right);
            return rotateLeft(node);
        }
        return node;
    }

    private static Node rotateLeft(Node node) {
        Node root = node.right;
        node.right = root.left;
        root.left = node;
        refresh(node); refresh(root);
        return root;
    }

    private static Node rotateRight(Node node) {
        Node root = node.left;
        node.left = root.right;
        root.right = node;
        refresh(node); refresh(root);
        return root;
    }

    private static void refresh(Node node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
        node.mask = (1L << node.partition) | mask(node.left) | mask(node.right);
    }

    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static long mask(Node node) { return node == null ? 0 : node.mask; }

    private static final class Node {
        long price, mask;
        int partition, count = 1, height = 1;
        Node left, right;
        Node(long price, int partition) {
            this.price = price;
            this.partition = partition;
            mask = 1L << partition;
        }
    }
}
