/**
 * Spring composition root for the Aeron node.
 *
 * <p>Beans in this package own configuration and infrastructure lifecycle only. They must not
 * become an alternate owner for trading state, Account Lane maps, snapshots, or command slots.</p>
 */
package com.surprising.aeron.service.bootstrap;
