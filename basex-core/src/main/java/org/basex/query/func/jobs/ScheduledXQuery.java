package org.basex.query.func.jobs;

import static org.basex.query.QueryError.*;

import java.math.*;
import java.util.*;
import java.util.Map.*;

import org.basex.core.*;
import org.basex.core.jobs.*;
import org.basex.core.locks.*;
import org.basex.data.*;
import org.basex.query.*;
import org.basex.query.iter.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.util.*;

/**
 * Asynchronous query.
 *
 * @author BaseX Team 2005-16, BSD License
 * @author Christian Gruen
 */
public final class ScheduledXQuery extends Job implements Runnable {
  /** Result. */
  private final JobResult result = new JobResult();
  /** Variable bindings. */
  private final HashMap<String, Value> bindings;
  /** Options. */
  private final ScheduleOptions opts;
  /** Input info. */
  private final InputInfo info;
  /** Caching flag. */
  private final boolean cache;
  /** Query string. */
  private final String query;
  /** Database context. */
  private final Context ctx;

  /** Query processor. */
  private QueryProcessor qp;

  /**
   * Constructor.
   * @param query query string
   * @param bindings variable bindings
   * @param opts options
   * @param info input info
   * @param qc query context
   * @throws QueryException query exception
   */
  ScheduledXQuery(final String query, final HashMap<String, Value> bindings,
      final ScheduleOptions opts, final InputInfo info, final QueryContext qc)
      throws QueryException {

    this.query = query;
    this.bindings = bindings;
    this.opts = opts;
    this.info = info;
    this.cache = opts.get(ScheduleOptions.CACHE);
    ctx = qc.context;

    final JobPool pool = ctx.jobs;
    final String id = job().id();
    if(cache) pool.results.put(id, result);

    // check when job is to be started
    final String del = opts.get(ScheduleOptions.START);
    long delay = 0;
    if(!del.isEmpty()) {
      qc.initDateTime();
      if(Dur.DTD.matcher(del).matches()) {
        delay = ms(new DTDur(Token.token(del), info));
      } else if(ADate.TIME.matcher(del).matches()) {
        delay = ms(new DTDur(new Tim(Token.token(del), info), (ADate) qc.time, info));
        while(delay <= 0) delay += 86400;
      } else {
        delay = ms(new DTDur(new Dtm(Token.token(del), info), (ADate) qc.datm, info));
      }
      if(delay < 0) throw JOBS_RANGE.get(info, del);
    }

    // check when job is to be repeated
    long interval = 0;
    final String inter = opts.get(ScheduleOptions.INTERVAL);
    if(!inter.isEmpty()) interval = ms(new DTDur(Token.token(inter), info));
    if(interval < 1000 && interval != 0) throw JOBS_RANGE.get(info, inter);

    // start thread and wait until job is registered or time was assigned
    pool.scheduleJob(this, delay, interval);

    // no delay: wait until query is queued
    do Performance.sleep(1); while(delay == 0 && pool.active.get(id) == null && !result.finished());
  }

  /**
   * Extracts the seconds from the specified date/duration item and returns it as milliseconds.
   * @param date date or duration
   * @return milliseconds
   */
  private long ms(final ADateDur date) {
    return date.sec.multiply(BigDecimal.valueOf(1000)).longValue();
  }

  @Override
  public void run() {
    try {
      // parse, push and register query. order is important!
      qp = parse();
      pushJob(qp);
      register(ctx);
      result.value = copy(qp.iter(), ctx, qp.qc);
    } catch(final JobException ex) {
      // query was interrupted: remove cached result
      ctx.jobs.results.remove(job().id());
    } catch(final QueryException ex) {
      result.exception = ex;
    } catch(final Throwable ex) {
      result.exception = BXXQ_UNEXPECTED_X.get(info, ex);
    } finally {
      // close and invalidate query after result has been assigned. order is important!
      result.time = System.currentTimeMillis();
      if(cache) ctx.jobs.scheduleResult(this);

      if(qp != null) {
        qp.close();
        unregister(ctx);
        popJob();
        qp = null;
      }
    }
  }

  @Override
  public void databases(final LockResult lr) {
    qp.databases(lr);
  }

  /**
   * Returns a query processor. Creates a new instance if none exists.
   * @return query processor
   * @throws QueryException query exception
   */
  QueryProcessor parse() throws QueryException {
    final QueryProcessor proc = new QueryProcessor(query, ctx);
    for(final Entry<String, Value> it : bindings.entrySet()) {
      final String key = it.getKey();
      final Value value = it.getValue();
      if(key.isEmpty()) proc.context(value);
      else proc.bind(key, value);
    }
    proc.parse(opts.get(ScheduleOptions.BASE_URI));
    updating = proc.updating;
    return proc;
  }

  /**
   * Creates a context-independent copy of the iterator results.
   * @param ctx database context
   * @param iter result iterator
   * @param qc query context
   * @return result
   * @throws QueryException query exception
   */
  static Value copy(final Iter iter, final Context ctx, final QueryContext qc)
      throws QueryException {

    final ValueBuilder vb = new ValueBuilder();
    for(Item it; (it = iter.next()) != null;) {
      qc.checkStop();
      if(it instanceof FItem) throw BASX_FITEM_X.get(null, it);
      final Data data = it.data();
      if(data != null && !data.inMemory()) it = ((DBNode) it).dbNodeCopy(ctx.options);
      vb.add(it);
    }
    return vb.value();
  }

  @Override
  public String toString() {
    return query;
  }
}
