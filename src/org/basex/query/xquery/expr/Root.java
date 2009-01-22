package org.basex.query.xquery.expr;

import static org.basex.query.xquery.XQText.*;
import org.basex.query.xquery.XQException;
import org.basex.query.xquery.XQContext;
import org.basex.query.xquery.item.Item;
import org.basex.query.xquery.item.Nod;
import org.basex.query.xquery.iter.Iter;
import org.basex.query.xquery.iter.SeqIter;
import org.basex.query.xquery.util.Err;

/**
 * Root node.
 * 
 * @author Workgroup DBIS, University of Konstanz 2005-08, ISC License
 * @author Christian Gruen
 */
public final class Root extends Simple {
  @Override
  public Iter iter(final XQContext ctx) throws XQException {
    final Iter iter = checkCtx(ctx);;
    final SeqIter ir = new SeqIter();
    Item i;
    while((i = iter.next()) != null) {
      final Nod n = root(i);
      if(n == null) Err.or(CTXNODE, this);
      ir.add(n);
    }
    return ir;
  }
  
  /**
   * Returns the root node of the specified item.
   * @param i input node
   * @return root node
   */
  public Nod root(final Item i) {
    if(!i.node()) return null;
    Nod n = (Nod) i;
    while(n.parent() != null) n = n.parent();
    return n;
  }

  @Override
  public Return returned(final XQContext ctx) {
    return Return.NOD;
  }

  @Override
  public boolean sameAs(final Expr cmp) {
    return cmp instanceof Root;
  }

  @Override
  public String toString() {
    return "root()";
  }
}
