package org.basex.query.ft;

import java.io.IOException;
import org.basex.data.Serializer;
import org.basex.query.IndexContext;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.expr.Simple;
import org.basex.query.item.FTItem;
import org.basex.query.item.Item;
import org.basex.query.iter.FTIter;
import org.basex.query.iter.Iter;
import org.basex.util.Tokenizer;

/**
 * FTContains expression with index access.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public final class FTIndexAccess extends Simple {
  /** Full-text expression. */
  final FTExpr ftexpr;
  /** Full-text parser. */
  final Tokenizer ft;
  /** Index context. */
  final IndexContext ictx;

  /**
   * Constructor.
   * @param ex contains, select and optional ignore expression
   * @param ftt tokenizer
   * @param ic index context
   */
  public FTIndexAccess(final FTExpr ex, final Tokenizer ftt,
      final IndexContext ic) {
    ftexpr = ex;
    ft = ftt;
    ictx = ic;
  }

  @Override
  public Iter iter(final QueryContext ctx) throws QueryException {
    final FTIter ir = ftexpr.iter(ctx);

    return new Iter() {
      @Override
      public Item next() throws QueryException {
        final Tokenizer tmp = ctx.fttoken;
        ctx.fttoken = ft;
        final FTItem it = ir.next();
        ctx.fttoken = tmp;

        //if(it != null) System.out.println(it.all);

        // add entry to visualization
        if(ctx.ftpos != null && it != null) ctx.ftpos.add(it.pre, it.all);
        return it;
      }
    };
  }

  @Override
  public void plan(final Serializer ser) throws IOException {
    ser.openElement(this);
    ftexpr.plan(ser);
    ser.closeElement();
  }

  @Override
  public boolean duplicates(final QueryContext ctx) {
    return ictx.dupl;
  }

  @Override
  public String toString() {
    return name() + "(" + ftexpr + ")";
  }
}
