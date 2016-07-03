package org.basex.query.func.jobs;

import org.basex.util.options.*;

/** Scheduling options. */
public final class ScheduleOptions extends Options {
  /** Query base-uri. */
  public static final StringOption BASE_URI = new StringOption("base-uri");
  /** Cache result. */
  public static final BooleanOption CACHE = new BooleanOption("cache", false);
  /** Start date/time/duration. */
  public static final StringOption START = new StringOption("start", "");
  /** Interval after which query will be repeated. */
  public static final StringOption INTERVAL = new StringOption("repeat", "");
}
