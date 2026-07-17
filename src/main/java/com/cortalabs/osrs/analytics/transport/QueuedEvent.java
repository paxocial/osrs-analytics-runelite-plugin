/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.dto.PluginPayload;
import java.util.UUID;

/**
 * One queued telemetry event awaiting transport.
 *
 * <p>{@link #eventId} is a stable client-side idempotency key. It is preserved
 * across retries (the same {@code QueuedEvent} instance is requeued on failure)
 * so a resend represents the same discrete event. It is intentionally NOT part
 * of the serialized wire payload: the live backend contract does not define an
 * {@code event_id} field, and the contract is the source of truth. The id is
 * retained for client-side de-duplication, log correlation, and forward
 * compatibility if the backend later adopts event-id idempotency.
 */
public final class QueuedEvent
{
	private final String eventId;
	private final EventCategory category;
	private final PluginPayload payload;

	/** Number of send attempts made for this event. */
	private int attempts;

	public QueuedEvent(EventCategory category, PluginPayload payload)
	{
		this.eventId = UUID.randomUUID().toString();
		this.category = category;
		this.payload = payload;
	}

	public String getEventId()
	{
		return eventId;
	}

	public EventCategory getCategory()
	{
		return category;
	}

	public PluginPayload getPayload()
	{
		return payload;
	}

	public int getAttempts()
	{
		return attempts;
	}

	public void markAttempt()
	{
		attempts++;
	}

	/** RSN this event is attributed to; batches never mix RSNs. */
	public String rsn()
	{
		return payload.rsn;
	}
}
