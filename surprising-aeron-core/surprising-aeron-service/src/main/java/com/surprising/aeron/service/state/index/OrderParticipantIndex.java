package com.surprising.aeron.service.state.index;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.TradingDependencyMask;
import com.surprising.aeron.service.state.model.CoreOrderState;

/** Owner-only price/participant reference counts, rebuilt from active orders after recovery.
 * Subtree masks allow a crossing-price range query without walking orders or price levels. */
final class OrderParticipantIndex {
    private Node bids, asks;
    /** 与账户状态相同的恢复后路由，用于保护物理 Lane 内的有序执行。 */
    private final com.surprising.aeron.service.state.LaneTopology topology;

    OrderParticipantIndex(com.surprising.aeron.service.state.LaneTopology topology) { this.topology = topology; }

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

    long counterpartyLanes(CoreOrderSide incomingSide, long limitPrice) {
        Node node = incomingSide == CoreOrderSide.BUY ? asks : bids;
        if (limitPrice == 0) return laneMask(node);
        long result = 0;
        while (node != null) {
            boolean crosses = incomingSide == CoreOrderSide.BUY ? node.price <= limitPrice : node.price >= limitPrice;
            if (crosses) {
                result |= node.ownLaneMask | laneMask(incomingSide == CoreOrderSide.BUY ? node.left : node.right);
                node = incomingSide == CoreOrderSide.BUY ? node.right : node.left;
            } else node = incomingSide == CoreOrderSide.BUY ? node.left : node.right;
        }
        return result;
    }

    void add(CoreOrderState order) { change(order, 1); }
    void remove(CoreOrderState order) { change(order, -1); }

    boolean contains(CoreOrderSide side, long price, long userId) {
        return userId > 0 && contains(side == CoreOrderSide.BUY ? asks : bids, side, price, userId);
    }

    private static boolean contains(Node node, CoreOrderSide side, long price, long userId) {
        if (node == null || (node.mask & TradingDependencyMask.account(userId)) == 0) return false;
        if (price != 0 && (side == CoreOrderSide.BUY ? node.price > price : node.price < price))
            return contains(side == CoreOrderSide.BUY ? node.left : node.right, side, price, userId);
        return node.userId == userId || contains(node.left, side, price, userId)
                || contains(node.right, side, price, userId);
    }

    boolean overlaps(CoreOrderSide side, long price, OrderParticipantIndex other,
                     CoreOrderSide otherSide, long otherPrice) {
        long common = counterparties(side, price) & other.counterparties(otherSide, otherPrice);
        return common != 0 && overlaps(side == CoreOrderSide.BUY ? asks : bids, side, price,
                other, otherSide, otherPrice, common);
    }

    private static boolean overlaps(Node node, CoreOrderSide side, long price, OrderParticipantIndex other,
                                    CoreOrderSide otherSide, long otherPrice, long common) {
        if (node == null || (node.mask & common) == 0) return false;
        if (price != 0 && (side == CoreOrderSide.BUY ? node.price > price : node.price < price))
            return overlaps(side == CoreOrderSide.BUY ? node.left : node.right, side, price,
                    other, otherSide, otherPrice, common);
        return ((TradingDependencyMask.account(node.userId) & common) != 0 && other.contains(otherSide, otherPrice, node.userId))
                || overlaps(node.left, side, price, other, otherSide, otherPrice, common)
                || overlaps(node.right, side, price, other, otherSide, otherPrice, common);
    }

    private void change(CoreOrderState order, int delta) {
        if (order.side() == CoreOrderSide.BUY) bids = change(bids, order.matchingPriceTicks(), order.userId(), delta);
        else asks = change(asks, order.matchingPriceTicks(), order.userId(), delta);
    }

    private Node change(Node node, long price, long userId, int delta) {
        if (node == null) {
            if (delta < 0) throw new IllegalStateException("active order participant count underflow");
            return new Node(price, userId, topology.accountLaneMask(userId));
        }
        int compared = Long.compare(price, node.price);
        if (compared == 0) compared = Long.compare(userId, node.userId);
        if (compared < 0) node.left = change(node.left, price, userId, delta);
        else if (compared > 0) node.right = change(node.right, price, userId, delta);
        else {
            node.count = Math.addExact(node.count, delta);
            if (node.count == 0) {
                if (node.left == null) return node.right;
                if (node.right == null) return node.left;
                Node successor = node.right;
                while (successor.left != null) successor = successor.left;
                node.price = successor.price;
                node.partition = successor.partition;
                node.userId = successor.userId;
                node.ownLaneMask = successor.ownLaneMask;
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
        node.laneMask = node.ownLaneMask | laneMask(node.left) | laneMask(node.right);
    }

    private static long laneMask(Node node) { return node == null ? 0 : node.laneMask; }

    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static long mask(Node node) { return node == null ? 0 : node.mask; }

    private static final class Node {
        long price, mask, userId;
        /** 本参与者与整个子树涉及的账户分区；不增加订单副本。 */
        long ownLaneMask, laneMask;
        int partition, count = 1, height = 1;
        Node left, right;
        Node(long price, long userId, long ownLaneMask) {
            this.ownLaneMask = this.laneMask = ownLaneMask;
            this.price = price;
            this.userId = userId;
            this.partition = TradingDependencyMask.partition(userId);
            mask = 1L << partition;
        }
    }
}
