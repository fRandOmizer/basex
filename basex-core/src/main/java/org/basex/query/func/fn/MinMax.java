package org.basex.query.func.fn;

import static org.basex.query.util.Err.*;
import static org.basex.query.value.type.AtomType.*;

import org.basex.query.*;
import org.basex.query.expr.CmpV.*;
import org.basex.query.func.*;
import org.basex.query.iter.*;
import org.basex.query.util.*;
import org.basex.query.value.item.*;
import org.basex.query.value.type.*;

/**
 * Min/max functions.
 *
 * @author BaseX Team 2005-14, BSD License
 * @author Christian Gruen
 */
abstract class MinMax extends StandardFunc {
  /**
   * Returns a minimum or maximum item.
   * @param cmp comparator
   * @param qc query context
   * @return resulting item
   * @throws QueryException query exception
   */
  protected Item minmax(final OpV cmp, final QueryContext qc) throws QueryException {
    final Collation coll = toCollation(1, qc);

    final Iter iter = exprs[0].atomIter(qc, info);
    Item rs = iter.next();
    if(rs == null) return null;

    // check if first item is comparable
    cmp.eval(rs, rs, coll, info);

    // strings
    if(rs instanceof AStr) {
      for(Item it; (it = iter.next()) != null;) {
        if(!(it instanceof AStr)) throw EXPTYPE_X_X_X.get(info, rs.type, it.type, it);
        if(cmp.eval(rs, it, coll, info)) rs = it;
      }
      return rs;
    }
    // dates, durations, booleans, binary values
    if(rs instanceof ADate || rs instanceof Dur || rs instanceof Bin || rs.type == BLN) {
      for(Item it; (it = iter.next()) != null;) {
        if(rs.type != it.type) throw EXPTYPE_X_X_X.get(info, rs.type, it.type, it);
        if(cmp.eval(rs, it, coll, info)) rs = it;
      }
      return rs;
    }
    // numbers
    if(rs.type.isUntyped()) rs = DBL.cast(rs, qc, sc, info);
    for(Item it; (it = iter.next()) != null;) {
      final Type t = numType(rs, it);
      if(cmp.eval(rs, it, coll, info) || Double.isNaN(it.dbl(info))) rs = it;
      if(rs.type != t) rs = (Item) t.cast(rs, qc, sc, info);
    }
    return rs;
  }

  /**
   * Returns the numeric type with the highest precedence.
   * @param res result item
   * @param it new item
   * @return result
   * @throws QueryException query exception
   */
  private Type numType(final Item res, final Item it) throws QueryException {
    final Type ti = it.type;
    if(ti.isUntyped()) return DBL;
    final Type tr = res.type;
    if(!(it instanceof ANum)) throw EXPTYPE_X_X_X.get(info, tr, ti, it);

    if(tr == ti) return tr;
    if(tr == DBL || ti == DBL) return DBL;
    if(tr == FLT || ti == FLT) return FLT;
    if(tr == DEC || ti == DEC) return DEC;
    return ITR;
  }
}