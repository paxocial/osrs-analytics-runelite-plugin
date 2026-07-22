/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.dto.LivePosition;
import com.cortalabs.osrs.analytics.dto.PluginPayload;
import java.util.UUID;

/**
 * One queued telemetry event awaiting transport.
 *
 * <p>{@link #eventId} is a stable client-side idempotency key. It is preserved
 * across retries (the same {@code QueuedEvent} instance is requeued on failure)
 * so a resend represents the same discrete event. The live backend contract
 * requires that identity on every populated child, so the constructor stamps it
 * onto the serialized payload before the event can enter the queue.
 */
public final class QueuedEvent
{
	private final String eventId;
	private final EventCategory category;
	private final PluginPayload payload;
	private final LivePosition position;

	/** Number of send attempts made for this event. */
	private int attempts;

	public QueuedEvent(EventCategory category, PluginPayload payload)
	{
		this(category, payload, UUID.randomUUID().toString(), null);
		payload.eventId = eventId;
	}

	private QueuedEvent(
		EventCategory category,
		PluginPayload payload,
		String eventId,
		LivePosition position)
	{
		this.eventId = eventId;
		this.category = category;
		this.payload = payload;
		this.position = position;
	}

	/**
	 * Create a retry-stable transport marker for the batch's top-level position.
	 * The context supplies only batch identity; the marker itself is never emitted
	 * as a category row.
	 */
	static QueuedEvent position(PluginPayload context, LivePosition position)
	{
		String eventId = UUID.randomUUID().toString();
		position.eventId = eventId;
		return new QueuedEvent(EventCategory.POSITION, context, eventId, position);
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

	/** Position carried by a transport-only marker, else {@code null}. */
	LivePosition position()
	{
		return position;
	}
}
