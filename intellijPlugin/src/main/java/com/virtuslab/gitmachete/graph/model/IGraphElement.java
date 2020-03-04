package com.virtuslab.gitmachete.graph.model;

import java.util.List;

import com.intellij.ui.SimpleTextAttributes;

import com.virtuslab.gitmachete.graph.GraphEdgeColor;

public interface IGraphElement {

  /**
   * @return The index of element above (in table) that is connected directly in graph to this one.
   */
  int getUpElementIndex();

  /**
   * @return Indexes of elements below (in table) that are connected directly in graph to this one.
   */
  List<Integer> getDownElementIndexes();

  /** @return The text (commit message/branch name) to be displayed in the table. */
  String getValue();

  /** @return Attributes (eg. boldness) to be used by the displayed text. */
  SimpleTextAttributes getAttributes();

  GraphEdgeColor getGraphEdgeColor();

  boolean hasBulletPoint();

  boolean isBranch();
}
