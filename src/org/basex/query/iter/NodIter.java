package org.basex.query.iter;

import org.basex.query.item.Item;
import org.basex.query.item.Nod;
import org.basex.query.item.Seq;
import org.basex.util.Array;

/**
 * Simple node Iterator, ignoring duplicates.
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public final class NodIter extends NodeIter {
  /** Items. */
  public Nod[] item;
  /** Size. */
  public int size;

  /** Iterator. */
  private int pos = -1;
  /** Sort flag. */
  private boolean sort;
  /** Flag for possible duplicates. */
  private boolean dupl;

  /**
   * Constructor.
   */
  public NodIter() {
    this(false);
  }

  /**
   * Constructor.
   * @param d returns if the iterator might return duplicates
   */
  public NodIter(final boolean d) {
    item = new Nod[1];
    dupl = d;
  }

  /**
   * Deletes a value at the specified position.
   * @param p deletion position
   */
  public void delete(final int p) {
    Array.move(item, p + 1, -1, --size - p);
  }

  /**
   * Adds a node.
   * @param n node to be added
   */
  public void add(final Nod n) {
    if(size == item.length) resize();
    if(dupl && !sort) sort = size != 0 && item[size - 1].diff(n) > 0;
    item[size++] = n;
  }

  /**
   * Resizes the sequence array.
   */
  private void resize() {
    final Nod[] tmp = new Nod[size << 1];
    System.arraycopy(item, 0, tmp, 0, size);
    item = tmp;
  }

  @Override
  public boolean reset() {
    pos = -1;
    return true;
  }

  @Override
  public Nod next() {
    if(dupl) sort(sort);
    return ++pos < size ? item[pos] : null;
  }

  @Override
  public Item get(final long i) {
    return i < size ? item[(int) i] : null;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public Item finish() {
    if(dupl) sort(sort);
    return Seq.get(item, size);
  }

  /**
   * Sorts the nodes.
   * @param force force sort
   */
  public void sort(final boolean force) {
    dupl = false;
    if(size > 1) {
      // sort arrays and remove duplicates
      if(force) sort(0, size);
      int i = 1;
      for(int j = 1; j < size; j++) {
        if(!item[i - 1].is(item[j])) item[i++] = item[j];
      }
      size = i;
    }
  }
  
  /**
   * Recursively sorts the specified items via QuickSort
   * (derived from Java's sort algorithms).
   * @param s start position
   * @param e end position
   */
  private void sort(final int s, final int e) {
    if(e < 7) {
      for(int i = s; i < e + s; i++)
        for(int j = i; j > s && item[j - 1].diff(item[j]) > 0; j--) s(j, j - 1);
      return;
    }
    
    int m = s + (e >> 1);
    if(e > 7) {
      int l = s;
      int n = s + e - 1;
      if(e > 40) {
        final int k = e >>> 3;
        l = m(l, l + k, l + (k << 1));
        m = m(m - k, m, m + k);
        n = m(n - (k << 1), n - k, n);
      }
      m = m(l, m, n);
    }
    final Nod v = item[m];

    int a = s, b = a, c = s + e - 1, d = c;
    while(true) {
      while(b <= c) {
        final int h = item[b].diff(v);
        if(h > 0) break;
        if(h == 0) s(a++, b);
        b++;
      }
      while(c >= b) {
        final int h = item[c].diff(v);
        if(h < 0) break;
        if(h == 0) s(c, d--);
        c--;
      }
      if(b > c) break;
      s(b++, c--);
    }

    int k;
    final int n = s + e;
    k = Math.min(a - s, b - a);
    s(s, b - k, k);
    k = Math.min(d - c, n - d - 1);
    s(b, n - k, k);

    if((k = b - a) > 1) sort(s, k);
    if((k = d - c) > 1) sort(n - k, k);
  }

  /**
   * Swaps x[a .. (a+n-1)] with x[b .. (b+n-1)].
   * @param a first offset
   * @param b second offset
   * @param n number of values
   */
  private void s(final int a, final int b, final int n) {
    for(int i = 0; i < n; i++) s(a + i, b + i);
  }

  /**
   * Returns the index of the median of the three indexed integers.
   * @param a first offset
   * @param b second offset
   * @param c thirst offset
   * @return median
   */
  private int m(final int a, final int b, final int c) {
    return item[a].diff(item[b]) < 0 ?
      (item[b].diff(item[c]) < 0 ? b : item[a].diff(item[c]) < 0 ? c : a) :
       item[b].diff(item[c]) > 0 ? b : item[a].diff(item[c]) > 0 ? c : a;
  }

  /**
   * Swaps two entries.
   * @param a first position
   * @param b second position
   */
  private void s(final int a, final int b) {
    final Nod tmp = item[a];
    item[a] = item[b];
    item[b] = tmp;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("(");
    for(int v = 0; v != size; v++) {
      sb.append((v != 0 ? ", " : "") + item[v]);
      if(sb.length() > 15 && v + 1 != size) {
        sb.append(", ...");
        break;
      }
    }
    return sb.append(")").toString();
  }
}
