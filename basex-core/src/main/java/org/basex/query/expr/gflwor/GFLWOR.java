package org.basex.query.expr.gflwor;

import java.util.*;
import java.util.function.*;

import org.basex.query.*;
import org.basex.query.expr.*;
import org.basex.query.expr.path.*;
import org.basex.query.func.Function;
import org.basex.query.iter.*;
import org.basex.query.util.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.seq.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * General FLWOR expression.
 *
 * @author BaseX Team 2005-19, BSD License
 * @author Leo Woerteler
 */
public final class GFLWOR extends ParseExpr {
  /** FLWOR clauses. */
  public final LinkedList<Clause> clauses;
  /** Return expression. */
  public Expr rtrn;

  /**
   * Constructor.
   * @param info input info
   * @param clauses FLWOR clauses
   * @param rtrn return expression
   */
  public GFLWOR(final InputInfo info, final LinkedList<Clause> clauses, final Expr rtrn) {
    super(info, SeqType.ITEM_ZM);
    this.clauses = clauses;
    this.rtrn = rtrn;
  }

  /**
   * Creates a new evaluator for this FLWOR expression.
   * @return the evaluator
   */
  private Eval newEval() {
    Eval eval = new StartEval();
    for(final Clause clause : clauses) eval = clause.eval(eval);
    return eval;
  }

  @Override
  public Iter iter(final QueryContext qc) {
    return new Iter() {
      private final Eval ev = newEval();
      private Iter sub = Empty.ITER;

      @Override
      public Item next() throws QueryException {
        while(true) {
          final Item item = qc.next(sub);
          if(item != null) return item;
          if(!ev.next(qc)) {
            sub = null;
            return null;
          }
          sub = rtrn.iter(qc);
        }
      }
    };
  }

  @Override
  public Value value(final QueryContext qc) throws QueryException {
    final Eval eval = newEval();
    final ValueBuilder vb = new ValueBuilder(qc);
    while(eval.next(qc)) vb.add(rtrn.value(qc));
    return vb.value(this);
  }

  @Override
  public Expr compile(final CompileContext cc) throws QueryException {
    final ListIterator<Clause> iter = clauses.listIterator();
    try {
      while(iter.hasNext()) iter.next().compile(cc);
    } catch(final QueryException qe) {
      iter.remove();
      clauseError(qe, iter, cc);
    }
    try {
      rtrn = rtrn.compile(cc);
    } catch(final QueryException qe) {
      clauseError(qe, iter, cc);
    }
    return optimize(cc);
  }

  @Override
  public Expr optimize(final CompileContext cc) throws QueryException {
    flattenAnd();

    // apply all optimizations in a row until nothing changes anymore
    while(flattenReturn(cc) | flattenFor(cc) | unnestFLWR(cc) | forToLet(cc) | inlineLets(cc) |
        slideLetsOut(cc) | unusedVars(cc) | cleanDeadVars() | optimizeWhere(cc) | optimizePos(cc) |
        unnestLets(cc) | mergeLastClause());

    mergeWheres();

    // replace with expression of 'return' clause if all clauses were removed
    Expr expr;
    if(clauses.isEmpty()) {
      expr = rtrn;
    } else if(clauses.getFirst() instanceof Where) {
      // replace with 'if' expression if FLWOR starts with 'where'
      final Where where = (Where) clauses.removeFirst();
      final Expr branch = clauses.isEmpty() ? rtrn : this;
      expr = new If(info, where.expr, branch).optimize(cc);
    } else {
      exprType.assign(rtrn.seqType().type, calcSize(true));
      expr = simplify(cc);
    }

    if(expr == rtrn) {
      cc.info(QueryText.OPTSIMPLE_X_X, (Supplier<?>) this::description, expr);
      return expr;
    }
    return cc.replaceWith(this, expr);
  }

  /**
   * Simplifies a FLWOR expression.
   * @param cc compilation context
   * @return original or optimized expression
   * @throws QueryException query exception
   */
  private Expr simplify(final CompileContext cc) throws QueryException {
    // skip non-leave if iterated items are non-deterministic
    for(final Clause clause : clauses) {
      if(clause.has(Flag.NDT, Flag.UPD)) return this;
    }

    // return empty sequence if expression will yield no results
    // for $i in 1 to 2 return ()  ->  ()
    final long size = size();
    if(size == 0 && !rtrn.has(Flag.CNS, Flag.NDT, Flag.UPD)) {
      return Empty.VALUE;
    }

    // leave if return clause relies on variables
    for(final Clause clause : clauses) {
      for(final Var var : clause.vars()) {
        if(rtrn.count(var) != VarUsage.NEVER) return this;
      }
    }

    // single result: return expression of return clause
    // for $i in 1 return <x/>  ->  <x/>
    if(size == 1) return rtrn;

    // leave if exact number of iterated items is unknown
    final long[] minMax = calcSize(false);
    final long min = minMax[0], max = minMax[1];
    if(min != max) return this;

    // leave if return clause creates new nodes
    if(rtrn.has(Flag.CNS, Flag.NDT, Flag.UPD)) return this;

    // create replicator
    // for $i in 1 to 2 return (3,4)  ->  util:replicate((3, 4), 2)
    return cc.function(Function._UTIL_REPLICATE, info, rtrn, Int.get(min));
  }

  /**
   * Computes the number of results of this FLWOR expression.
   * @param ret include return clause
   * @return min/max values (min: 0 or more, max: -1 or more)
   */
  private long[] calcSize(final boolean ret) {
    final long[] minMax = { 1, 1 };
    for(final Clause clause : clauses) {
      if(minMax[1] != 0) clause.calcSize(minMax);
    }
    if(ret && minMax[1] != 0) {
      final long size = rtrn.size();
      minMax[0] *= Math.max(size, 0);
      if(minMax[1] > 0) minMax[1] = size >= 0 ? minMax[1] * size : -1;
      else if(size == 0) minMax[1] = 0;
    }
    return minMax;
  }

  /**
   * Rewrites and expressions in predicates to where clauses.
   */
  private void flattenAnd() {
    // rewrite and operators in where clauses to single clauses
    final ListIterator<Clause> iter = clauses.listIterator();
    while(iter.hasNext()) {
      final Clause clause = iter.next();
      if(clause instanceof Where) {
        final Where where = (Where) clause;
        if(where.expr instanceof And) {
          iter.remove();
          for(final Expr expr : ((Arr) where.expr).exprs) iter.add(new Where(expr, where.info));
        }
      }
    }
  }

  /**
   * Tries to convert 'for' clauses that iterate over a single item into 'let' bindings.
   * @param cc compilation context
   * @return change flag
   */
  private boolean forToLet(final CompileContext cc) {
    boolean changed = false;
    for(int i = clauses.size(); --i >= 0;) {
      final Clause clause = clauses.get(i);
      if(clause instanceof For && ((For) clause).asLet(clauses, i)) {
        cc.info(QueryText.OPTFORTOLET_X, clause);
        changed = true;
      }
    }
    return changed;
  }

  /**
   * Removes unused variables or simplifies their expressions.
   * @param cc compilation context
   * @return change flag
   * @throws QueryException query exception
   */
  private boolean unusedVars(final CompileContext cc) throws QueryException {
    boolean changed = false;
    final ListIterator<Clause> iter = clauses.listIterator();
    while(iter.hasNext()) iter.next();

    // loop backwards (removed clauses may contain references to variables of outer clauses)
    while(iter.hasPrevious()) {
      final int pos = iter.previousIndex();
      final Clause clause = iter.previous();
      if(clause instanceof Let) {
        // completely remove 'let' clause
        final Let lt = (Let) clause;
        if(count(lt.var, pos + 1) == VarUsage.NEVER && !lt.has(Flag.NDT)) {
          cc.info(QueryText.OPTVAR_X, lt.var);
          // check type before removing variable (see {@link FuncType})
          lt.var.checkType(lt.expr);
          iter.remove();
          changed = true;
        }
      } else if(clause instanceof For) {
        // replace deterministic expression with cheaper singleton sequence
        // for $i in 1 to 3 return <a/> -> for $i in util:replicate('', 3) return $i
        final For fr = (For) clause;
        final long fs = fr.expr.size();
        if(fs > 1 && fr.var.declType == null && !(fr.expr instanceof SingletonSeq) &&
            !fr.has(Flag.NDT) && count(fr.var, pos + 1) == VarUsage.NEVER) {
          fr.expr = cc.replaceWith(fr.expr, SingletonSeq.get(Str.ZERO, fs));
          changed = true;
        }
        // remove scoring variable (include current iteration in count)
        // for $i score $s in <a/> return $i -> for $i in <a/> return $i
        if(fr.score != null && count(fr.score, pos) == VarUsage.NEVER) {
          cc.info(QueryText.OPTVAR_X, fr.score);
          fr.score = null;
          changed = true;
        }
        // remove positional variable (include current iteration in count)
        // for $i pos $p in () return $p -> for $i in () return $p; later -> ()
        if(fr.pos != null && count(fr.pos, pos) == VarUsage.NEVER) {
          cc.info(QueryText.OPTVAR_X, fr.pos);
          fr.pos = null;
          changed = true;
        }
      }
    }
    return changed;
  }

  /**
   * Inlines 'let' expressions if they are used only once (and not in a loop).
   * @param cc compilation context
   * @return change flag
   * @throws QueryException query exception
   */
  private boolean inlineLets(final CompileContext cc) throws QueryException {
    boolean changed = false, changing;
    do {
      changing = false;
      final ListIterator<Clause> iter = clauses.listIterator();
      while(iter.hasNext()) {
        final Clause clause = iter.next();
        if(!(clause instanceof Let)) continue;

        final int next = iter.nextIndex();
        final Let lt = (Let) clause;
        final Expr expr = lt.expr;
        if(expr.has(Flag.NDT)) continue;

        final Var var = lt.var;
        final BooleanSupplier inlineable = () -> {
          for(final ListIterator<Clause> i = clauses.listIterator(next); i.hasNext();) {
            if(!i.next().inlineable(var)) return false;
          }
          return rtrn.inlineable(var);
        };

        // inline simple values
        boolean inline = expr instanceof Value;

        if(!inline && expr instanceof VarRef) {
          // inline variable references without type checks
          inline = !var.checksType();
        }
        if(!inline && expr instanceof ContextValue) {
          // inline context values
          // allowed: 1[let $x := . return $x] -> 1[.]
          // illegal: 1[let $x := . return <a/>[$x = 1]]
          inline = inlineable.getAsBoolean();
        }
        if(!inline && count(var, next) == VarUsage.ONCE && !expr.has(Flag.CNS)) {
          // inline expressions that occur once, but do not construct nodes
          // e.g. let $x := <X/> return <X xmlns='xx'>{ $x/self::X }</X>
          // inline top-level context references
          // e.g. (1 to 5)[let $p := position() return $p = 1] -> (1 to 5)[position() = 1]
          inline = !expr.has(Flag.CTX) || inlineable.getAsBoolean();
        }
        if(!inline && expr instanceof Path) {
          // inline cheap path expressions with single result
          // e.g. doc('x.xml')/root
          inline = expr.size() == 1 && !expr.has(Flag.NDT, Flag.CNS) &&
              (!expr.has(Flag.CTX) || inlineable.getAsBoolean());
        }

        if(inline) {
          cc.info(QueryText.OPTINLINE_X, var);
          inline(cc, var, lt.inlineExpr(cc), iter);
          clauses.remove(lt);
          changing = changed = true;
          // continue from the beginning as clauses below could have been deleted
          break;
        }
      }
    } while(changing);
    return changed;
  }

  /**
   * Unnests basic FLWR expressions in 'for' or 'let' clauses.
   * @param cc compilation context
   * @return change flag
   * @throws QueryException query exception
   */
  private boolean unnestFLWR(final CompileContext cc) throws QueryException {
    boolean changed = false, thisRound;
    do {
      thisRound = false;
      final ListIterator<Clause> iter = clauses.listIterator();
      while(iter.hasNext()) {
        final Clause clause = iter.next();
        final boolean isFor = clause instanceof For, isLet = clause instanceof Let;
        if(isFor) {
          // for $x in (for $y in A (...) return B) -> for $y in A (...) for $x in B
          final For fr = (For) clause;
          if(!fr.empty && fr.pos == null && fr.expr instanceof GFLWOR) {
            final GFLWOR fl = (GFLWOR) fr.expr;
            if(fl.isFLW()) {
              cc.info(QueryText.OPTFLAT_X_X, (Supplier<?>) this::description, fr.var);
              iter.remove();
              for(final Clause cls : fl.clauses) iter.add(cls);
              fr.expr = fl.rtrn;
              iter.add(fr);
              thisRound = changed = true;
            }
          }
        }

        if(!thisRound && (isFor || isLet)) {
          // let $x := (let $y := E return F) -> let $y := E let $x := F
          final Expr expr = isFor ? ((For) clause).expr : ((Let) clause).expr;
          if(expr instanceof GFLWOR) {
            final GFLWOR fl = (GFLWOR) expr;
            final LinkedList<Clause> cls = fl.clauses;
            if(cls.getFirst() instanceof Let) {
              // remove the binding from the outer clauses
              iter.remove();

              // propagate all leading 'let' bindings into outer clauses
              do iter.add(cls.removeFirst());
              while(!cls.isEmpty() && cls.getFirst() instanceof Let);

              // re-add the binding with new, reduced expression at the end
              final Expr rest = fl.clauses.isEmpty() ? fl.rtrn : fl.optimize(cc);
              if(isFor) ((For) clause).expr = rest;
              else ((Let) clause).expr = rest;
              iter.add(clause);
              thisRound = changed = true;
            }
          }
        }
      }
    } while(thisRound);
    return changed;
  }

  /**
   * Cleans dead entries from the tuples that {@link GroupBy} and {@link OrderBy} handle.
   * @return change flag
   */
  private boolean cleanDeadVars() {
    final IntObjMap<Var> decl = new IntObjMap<>();
    for(final Clause clause : clauses) {
      for(final Var var : clause.vars()) decl.put(var.id, var);
    }

    final BitArray used = new BitArray();
    final ASTVisitor marker = new ASTVisitor() {
      @Override
      public boolean used(final VarRef ref) {
        final int id = ref.var.id;
        if(decl.get(id) != null) used.set(id);
        return true;
      }
    };

    rtrn.accept(marker);
    boolean changed = false;
    for(int c = clauses.size(); --c >= 0;) {
      final Clause clause = clauses.get(c);
      changed |= clause.clean(decl, used);
      clause.accept(marker);
      for(final Var var : clause.vars()) used.clear(var.id);
    }
    return changed;
  }

  /**
   * Tries to slide 'let' expressions out of loops.
   * Care is taken that no unnecessary relocations are done.
   * @param cc compilation context
   * @return {@code true} if there were relocations, {@code false} otherwise
   */
  private boolean slideLetsOut(final CompileContext cc) {
    boolean changed = false;
    for(int c = 1; c < clauses.size(); c++) {
      final Clause clause = clauses.get(c);
      // do not move node constructors. example: for $x in 1 to 2 let $a := <x/> return $a
      if(!(clause instanceof Let) || clause.has(Flag.NDT, Flag.CNS)) continue;
      final Let let = (Let) clause;

      // find insertion position
      int insert = -1;
      for(int d = c; --d >= 0;) {
        final Clause curr = clauses.get(d);
        if(!curr.skippable(let)) break;
        // insert directly above the highest skippable 'for' or 'window' clause
        // this guarantees that no unnecessary swaps occur
        if(curr instanceof For || curr instanceof Window) insert = d;
      }

      if(insert >= 0) {
        cc.info(QueryText.OPTLET_X, let);
        clauses.add(insert, clauses.remove(c));
        changed = true;
        // it's safe to go on because clauses below the current one are never touched
      }
    }
    return changed;
  }

  /**
   * Slides where clauses upwards and removes those that do not filter anything.
   * @param cc compilation context
   * @return change flag
   * @throws QueryException query exception
   */
  private boolean optimizeWhere(final CompileContext cc) throws QueryException {
    boolean changed = false;
    final HashSet<For> fors = new HashSet<>();
    for(int i = 0; i < clauses.size(); i++) {
      final Clause clause = clauses.get(i);
      if(!(clause instanceof Where) || clause.has(Flag.NDT)) continue;
      final Where where = (Where) clause;

      if(where.expr instanceof Value) {
        // if test is always false, skip remaining tests (no results possible)
        if(!where.expr.ebv(cc.qc, where.info).bool(where.info)) {
          where.expr = Bln.FALSE;
          break;
        }
        // test is always true: remove it
        clauses.remove(i--);
        changed = true;
      } else {
        // find insertion position
        int insert = -1;
        for(int j = i; --j >= 0;) {
          final Clause curr = clauses.get(j);
          if(curr.has(Flag.NDT) || !curr.skippable(where)) break;
          // where clauses are always moved to avoid unnecessary computations,
          // but skipping only other where clauses can cause infinite loops
          if(!(curr instanceof Where)) insert = j;
        }

        if(insert >= 0) {
          clauses.add(insert, clauses.remove(i));
          changed = true;
          // it's safe to go on because clauses below the current one are never touched
        }

        // try to rewrite where clause to predicate
        final int newPos = insert < 0 ? i : insert;
        for(int b4 = newPos; --b4 >= 0;) {
          final Clause before = clauses.get(b4);
          if(before instanceof For) {
            final For fr = (For) before;
            if(fr.toPredicate(cc, where.expr)) {
              // for $i in ('a', 'b') where $i return $i -> for $i in ('a', 'b')[.] return $i
              fors.add((For) before);
              clauses.remove(newPos);
              i--;
              changed = true;
            }
          } else if(before instanceof Where) {
            continue;
          }
          break;
        }
      }
    }
    // trigger optimizations on rewritten expressions
    for(final For fr : fors) fr.expr = fr.expr.optimize(cc);
    if(changed) cc.info(QueryText.OPTWHERE);
    return changed;
  }

  /**
   * Rewrites positional variables to predicates.
   * @param cc compilation context
   * @return change flag
   * @throws QueryException query exception
   */
  private boolean optimizePos(final CompileContext cc) throws QueryException {
    boolean changed = false;
    for(int c = 0; c < clauses.size(); c++) {
      final Clause clause = clauses.get(c);
      if(!(clause instanceof For)) continue;

      final For pos = (For) clause;
      if(pos.pos == null) continue;

      // find where clause ($c = 1)
      for(int i = c + 1; i < clauses.size(); i++) {
        final Clause cl = clauses.get(i);
        if(!(cl instanceof Where)) {
          // stop if clause is no 'for' or 'let' expression or non-deterministic
          if(!(cl instanceof For || cl instanceof Let) || cl.has(Flag.NDT)) break;
          continue;
        }
        final Where where = (Where) cl;
        if(!(where.expr instanceof CmpIR)) continue;
        final CmpIR cmp = (CmpIR) where.expr;
        if(!(cmp.expr instanceof VarRef)) continue;

        // remove clause and ensure that the positional variable is only used once
        clauses.remove(i);
        if(count(pos.pos, c) == VarUsage.NEVER) {
          /* OLD: for $v at $pos in E where $pos = P ...
           * NEW: for $v in E[position() = P] ... */
          pos.addPredicate(ItrPos.get(cmp.min, cmp.max, cmp.info));
          pos.expr = pos.expr.optimize(cc);
          cc.info(QueryText.OPTPRED_X, cmp);
          changed = true;
        } else {
          // re-add variable, give up
          clauses.add(i, cl);
        }
        break;
      }
    }
    return changed;
  }

  /**
   * Merge last 'for' or 'let' clause with 'return' clause.
   * @return change flag
   */
  private boolean mergeLastClause() {
    if(!clauses.isEmpty() && clauses.getLast() instanceof ForLet && rtrn instanceof VarRef) {
      final ForLet last = (ForLet) clauses.getLast();
      // for $x in E return $x -> E
      if(last.var.is(((VarRef) rtrn).var) && !last.var.checksType() && !last.scoring) {
        clauses.removeLast();
        rtrn = last.expr;
        return true;
      }
    }
    return false;
  }

  /**
   * Flatten FLWOR expressions in 'for' clauses.
   * @param cc compilation context
   * @return change flag
   * @throws QueryException query exception
   */
  private boolean flattenFor(final CompileContext cc) throws QueryException {
    if(!clauses.isEmpty() && clauses.getFirst() instanceof For) {
      final For fst = (For) clauses.getFirst();
      if(!fst.empty) {
        // flat nested FLWOR expressions
        if(fst.expr instanceof GFLWOR) {
          // OLD: for $a at $p in for $x in (1 to 2) return $x + 1 return $p
          // NEW: for $a in (1 to 2) count $p return $p
          cc.info(QueryText.OPTFLAT_X_X, (Supplier<?>) this::description, fst.var);
          final GFLWOR sub = (GFLWOR) fst.expr;
          clauses.set(0, new For(fst.var, null, fst.score, sub.rtrn, false));
          if(fst.pos != null) clauses.add(1, new Count(fst.pos));
          clauses.addAll(0, sub.clauses);
          return true;
        }
        if(clauses.size() > 1 && clauses.get(1) instanceof Count) {
          final Count cnt = (Count) clauses.get(1);
          if(fst.pos != null) {
            final VarRef vr = new VarRef(cnt.info, fst.pos);
            clauses.set(1, new Let(cnt.var, vr.optimize(cc), false).optimize(cc));
          } else {
            clauses.set(0, new For(fst.var, cnt.var, fst.score, fst.expr, false).optimize(cc));
            clauses.remove(1);
          }
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Flatten FLWOR expressions in 'return' clauses.
   * @param cc compilation context
   * @return change flag
   */
  private boolean flattenReturn(final CompileContext cc) {
    if(!clauses.isEmpty() && rtrn instanceof GFLWOR) {
      final GFLWOR sub = (GFLWOR) rtrn;
      if(sub.isFLW()) {
        // flatten nested FLWOR expressions
        // OLD: for $a in (1 to 2) return let $f := <a>1</a> return $f + 1
        // NEW: for $a in (1 to 2) let $f := <a>1</a> return $f + 1
        final Clause clause = sub.clauses.getFirst();
        final ExprInfo var = clause instanceof ForLet ? ((ForLet) clause).var : clause;
        cc.info(QueryText.OPTFLAT_X_X, (Supplier<?>) this::description, var);
        clauses.addAll(sub.clauses);
        rtrn = sub.rtrn;
        return true;
      }
    }
    return false;
  }

  /**
   * Tries to slide 'let' expressions out of loops.
   * Care is taken that no unnecessary relocations are done.
   * @param cc compilation context
   * @return {@code true} if there were relocations, {@code false} otherwise
   * @throws QueryException query exception
   */
  private boolean unnestLets(final CompileContext cc) throws QueryException {
    if(!clauses.isEmpty()) {
      final TypeCheck tc = rtrn instanceof TypeCheck ? (TypeCheck) rtrn : null;
      if(rtrn instanceof GFLWOR || tc != null && tc.expr instanceof GFLWOR) {
        final GFLWOR sub = (GFLWOR) (tc == null ? rtrn : tc.expr);
        final Clause clause = sub.clauses.getFirst();
        if(clause instanceof Let) {
          /* example:
          function($a) as xs:integer+ {
            function($c) { ($c, if($a) then $a else $c) }(1[. = 1])
          }(2[. = 1])

          OLD:
          let $a := 2[. = 1]
          return (
            let $c := 1[. = 1]
            return ($c, if($a) then $a else $c)
          )

          NEW:
          let $a := 2[. = 1]
          let $c := 1[. = 1]
          return ($c, if($a) then $a else $c))
          */
          cc.info(QueryText.OPTFLAT_X_X, (Supplier<?>) this::description, ((Let) clause).var);

          // propagate all leading 'let' bindings into outer clauses
          final LinkedList<Clause> cls = sub.clauses;
          do {
            clauses.add(cls.removeFirst());
          } while(!cls.isEmpty() && cls.getFirst() instanceof Let);
          if(tc != null) tc.expr = sub.optimize(cc);
          rtrn = rtrn.optimize(cc);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Merges consecutive {@code where} clauses.
   */
  private void mergeWheres() {
    Where before = null;
    final Iterator<Clause> iter = clauses.iterator();
    while(iter.hasNext()) {
      final Clause clause = iter.next();
      if(clause instanceof Where) {
        final Where wh = (Where) clause;
        if(wh.expr == Bln.FALSE) return;
        if(before != null) {
          iter.remove();
          final Expr expr = before.expr;
          if(expr instanceof And) {
            final And and = (And) expr;
            and.exprs = ExprList.concat(and.exprs, wh.expr);
          } else {
            before.expr = new And(before.info, expr, wh.expr);
          }
        } else {
          before = wh;
        }
      } else {
        before = null;
      }
    }
  }

  @Override
  public boolean isVacuous() {
    return rtrn.isVacuous();
  }

  @Override
  public boolean has(final Flag... flags) {
    for(final Clause clause : clauses) {
      if(clause.has(flags)) return true;
    }
    return rtrn.has(flags);
  }

  @Override
  public boolean inlineable(final Var var) {
    for(final Clause clause : clauses) {
      if(!clause.inlineable(var)) return false;
    }
    return rtrn.inlineable(var);
  }

  @Override
  public VarUsage count(final Var var) {
    return count(var, 0);
  }

  /**
   * Counts the number of usages of the given variable starting from the given clause.
   * @param var variable
   * @param index start position
   * @return usage count
   */
  private VarUsage count(final Var var, final int index) {
    final long[] minMax = { 1, 1 };
    VarUsage uses = VarUsage.NEVER;
    final ListIterator<Clause> iter = clauses.listIterator(index);
    while(iter.hasNext()) {
      final Clause clause = iter.next();
      uses = uses.plus(clause.count(var).times(minMax[1]));
      clause.calcSize(minMax);
    }
    return uses.plus(rtrn.count(var).times(minMax[1]));
  }

  @Override
  public Expr inline(final Var var, final Expr ex, final CompileContext cc) throws QueryException {
    return inline(cc, var, ex, clauses.listIterator()) ? optimize(cc) : null;
  }

  /**
   * Inlines an expression bound to a given variable, starting at a specified clause.
   * @param cc compilation context
   * @param var variable
   * @param ex expression to inline
   * @param iter iterator at the position of the first clause to inline into
   * @return if changes occurred
   * @throws QueryException query exception
   */
  private boolean inline(final CompileContext cc, final Var var, final Expr ex,
      final ListIterator<Clause> iter) throws QueryException {

    boolean changed = false;
    while(iter.hasNext()) {
      final Clause clause = iter.next();
      try {
        final Clause cl = clause.inline(var, ex, cc);
        if(cl != null) {
          changed = true;
          iter.set(cl);
        }
      } catch(final QueryException qe) {
        iter.remove();
        return clauseError(qe, iter, cc);
      }
    }

    try {
      final Expr rt = rtrn.inline(var, ex, cc);
      if(rt != null) {
        changed = true;
        rtrn = rt;
      }
    } catch(final QueryException qe) {
      return clauseError(qe, iter, cc);
    }

    return changed;
  }

  /**
   * Tries to recover from a compile-time exception inside a FLWOR clause.
   * @param qe thrown exception
   * @param iter iterator positioned where the failing clause was before
   * @param cc compilation context
   * @return {@code true} if the GFLWOR expression has to stay
   * @throws QueryException query exception if the whole expression fails
   */
  private boolean clauseError(final QueryException qe, final ListIterator<Clause> iter,
      final CompileContext cc) throws QueryException {

    // check if an outer clause can prevent the error
    while(iter.hasPrevious()) {
      final Clause b4 = iter.previous();
      if(b4 instanceof For || b4 instanceof Window || b4 instanceof Where) {
        iter.next();
        while(iter.hasNext()) {
          iter.next();
          iter.remove();
        }
        rtrn = cc.error(qe, rtrn);
        return true;
      }
    }

    // error will always be thrown
    throw qe;
  }

  @Override
  public Expr copy(final CompileContext cc, final IntObjMap<Var> vm) {
    final LinkedList<Clause> cls = new LinkedList<>();
    for(final Clause clause : clauses) cls.add(clause.copy(cc, vm));
    return copyType(new GFLWOR(info, cls, rtrn.copy(cc, vm)));
  }

  /**
   * Checks if this FLWOR expression has only 'for', 'let' and 'where' clauses.
   * @return result of check
   */
  private boolean isFLW() {
    for(final Clause clause : clauses)
      if(!(clause instanceof For || clause instanceof Let || clause instanceof Where)) return false;
    return true;
  }

  @Override
  public boolean accept(final ASTVisitor visitor) {
    for(final Clause clause : clauses) {
      if(!clause.accept(visitor)) return false;
    }
    return rtrn.accept(visitor);
  }

  @Override
  public void checkUp() throws QueryException {
    for(final Clause clause : clauses) clause.checkUp();
    rtrn.checkUp();
  }

  @Override
  public void markTailCalls(final CompileContext cc) {
    final long[] minMax = { 1, 1 };
    for(final Clause clause : clauses) {
      clause.calcSize(minMax);
      if(minMax[1] < 0 || minMax[1] > 1) return;
    }
    rtrn.markTailCalls(cc);
  }

  @Override
  public int exprSize() {
    int size = 1;
    for(final Clause clause : clauses) size += clause.exprSize();
    return rtrn.exprSize() + size;
  }

  @Override
  public Expr typeCheck(final TypeCheck tc, final CompileContext cc) throws QueryException {
    if(tc.seqType().occ != Occ.ZERO_MORE) return null;
    rtrn = tc.check(rtrn, cc);
    return optimize(cc);
  }

  @Override
  public boolean equals(final Object obj) {
    if(this == obj) return true;
    if(!(obj instanceof GFLWOR)) return false;
    final GFLWOR g = (GFLWOR) obj;
    return clauses.equals(g.clauses) && rtrn.equals(g.rtrn);
  }

  @Override
  public String description() {
    return "FLWOR expression";
  }

  @Override
  public void plan(final QueryPlan plan) {
    plan.add(plan.create(this), clauses.toArray(new Clause[0]), rtrn);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    for(final Clause clause : clauses) sb.append(clause).append(' ');
    return sb.append(QueryText.RETURN).append(' ').append(rtrn).toString();
  }

  /** Start evaluator, doing nothing, once. */
  private static final class StartEval extends Eval {
    /** First-evaluation flag. */
    private boolean more;

    @Override
    public boolean next(final QueryContext qc) {
      if(more) return false;
      more = true;
      return true;
    }
  }
}
