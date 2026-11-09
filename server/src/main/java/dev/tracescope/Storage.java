package dev.tracescope;

import java.util.*;

public interface Storage {
  void insert(String table, List<Map<String, Object>> rows, String token);

  List<Map<String, Object>> query(String sql, Object... parameters);
}
