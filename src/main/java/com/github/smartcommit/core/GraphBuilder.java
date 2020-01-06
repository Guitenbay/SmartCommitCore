package com.github.smartcommit.core;

import com.github.smartcommit.core.visitor.MemberVisitor;
import com.github.smartcommit.core.visitor.MyNodeFinder;
import com.github.smartcommit.model.DiffFile;
import com.github.smartcommit.model.DiffHunk;
import com.github.smartcommit.model.EntityPool;
import com.github.smartcommit.model.constant.Version;
import com.github.smartcommit.model.entity.FieldInfo;
import com.github.smartcommit.model.entity.HunkInfo;
import com.github.smartcommit.model.entity.MethodInfo;
import com.github.smartcommit.model.graph.Edge;
import com.github.smartcommit.model.graph.EdgeType;
import com.github.smartcommit.model.graph.Node;
import com.github.smartcommit.model.graph.NodeType;
import com.github.smartcommit.util.JDTService;
import com.github.smartcommit.util.NameResolver;
import com.github.smartcommit.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.jgrapht.Graph;
import org.jgrapht.graph.builder.GraphTypeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** Build the semantic context graph of DiffHunks in Java files. */
public class GraphBuilder implements Callable<Graph<Node, Edge>> {

  private static final Logger logger = LoggerFactory.getLogger(GraphBuilder.class);

  private String srcDir;
  private List<DiffFile> diffFiles;
  private EntityPool entityPool;
  private Graph<Node, Edge> graph;

  public GraphBuilder(String srcDir) {
    this.srcDir = srcDir;
    this.diffFiles = new ArrayList<>();
  }

  public GraphBuilder(String srcDir, List<DiffFile> diffFiles) {
    this.srcDir = srcDir;
    this.diffFiles = diffFiles;
    this.entityPool = new EntityPool(srcDir);
    this.graph = initGraph();
  }

  /**
   * Initialize an empty Graph
   *
   * @return
   */
  public static Graph<Node, Edge> initGraph() {
    return GraphTypeBuilder.<Node, Edge>directed()
        .allowingMultipleEdges(true)
        .allowingSelfLoops(true) // recursion
        .edgeClass(Edge.class)
        .weighted(true)
        .buildGraph();
  }

  /**
   * Build the graph from java files
   *
   * @return
   */
  @Override
  public Graph<Node, Edge> call() {
    // get all java files by extension in the source directory
    Collection<File> javaFiles = FileUtils.listFiles(new File(srcDir), new String[] {"java"}, true);
    Set<String> srcPathSet = new HashSet<>();
    Set<String> srcFolderSet = new HashSet<>();
    for (File javaFile : javaFiles) {
      String srcPath = javaFile.getAbsolutePath();
      String srcFolderPath = javaFile.getParentFile().getAbsolutePath();
      srcPathSet.add(srcPath);
      srcFolderSet.add(srcFolderPath);
    }

    String[] srcPaths = new String[srcPathSet.size()];
    srcPathSet.toArray(srcPaths);
    NameResolver.setSrcPathSet(srcPathSet);
    String[] srcFolderPaths = new String[srcFolderSet.size()];
    srcFolderSet.toArray(srcFolderPaths);

    ASTParser parser = ASTParser.newParser(9);
    //        parser.setProject(WorkspaceUtilities.javaProject);
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setEnvironment(null, srcFolderPaths, null, true);
    parser.setResolveBindings(true);
    Map<String, String> options = new Hashtable<>();
    options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_8);
    options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
    options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
    parser.setCompilerOptions(options);
    parser.setBindingsRecovery(true);

    // Vertex: create nodes and nesting edges while visiting the ASTs
    parser.createASTs(
        srcPaths,
        null,
        new String[] {},
        new FileASTRequestor() {
          @Override
          public void acceptAST(String sourceFilePath, CompilationUnit cu) {
            try {
              // get the corresponding diff file
              Version version = Version.BASE;
              if (sourceFilePath.contains(
                  File.separator + Version.CURRENT.asString() + File.separator)) {
                version = Version.CURRENT;
              }
              Optional<DiffFile> diffFileOpt = getDiffFileByPath(sourceFilePath, version);
              if (diffFileOpt.isPresent()) {
                DiffFile diffFile = diffFileOpt.get();
                Map<String, Pair<Integer, Integer>> hunksPosition =
                    computeHunksPosition(diffFile, cu, version);

                // collect type/field/method infos and create nodes
                JDTService jdtService =
                    new JDTService(FileUtils.readFileToString(new File(sourceFilePath)));
                cu.accept(new MemberVisitor(diffFile.getIndex(), entityPool, graph, jdtService));

                // collect hunk infos and create nodes
                createHunkInfos(diffFile.getIndex(), hunksPosition, cu, jdtService);
              }
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        },
        null);

    // Edge: create inter-entity edges with the EntityPool and EntityInfo
    int edgeCount = graph.edgeSet().size();
    Map<String, MethodInfo> methodDecMap = entityPool.methodInfoMap;
    Map<String, FieldInfo> fieldDecMap = entityPool.fieldInfoMap;
    Map<String, HunkInfo> hunkMap = entityPool.hunkInfoMap;
    Map<IMethodBinding, MethodInfo> methodBindingMap = new HashMap<>();
    for (MethodInfo methodInfo : entityPool.methodInfoMap.values()) {
      methodBindingMap.put(methodInfo.methodBinding, methodInfo);
    }

    // 1. edges from method declaration
    for (MethodInfo methodInfo : methodDecMap.values()) {
      Node methodDeclNode = methodInfo.node;
      // method invocation
      for (IMethodBinding methodCall : methodInfo.methodCalls) {
        MethodInfo targetMethodInfo = methodBindingMap.get(methodCall);
        if (targetMethodInfo != null) {
          graph.addEdge(
              methodDeclNode, targetMethodInfo.node, new Edge(edgeCount++, EdgeType.CALL));
        }
      }

      // field access
      for (String fieldUse : methodInfo.fieldUses) {
        FieldInfo targetFieldInfo = fieldDecMap.get(fieldUse);
        if (targetFieldInfo != null) {
          graph.addEdge(
              methodDeclNode, targetFieldInfo.node, new Edge(edgeCount++, EdgeType.ACCESS));
        }
      }

      // return type(s)
      for (String type : methodInfo.returnTypes) {
        Optional<Node> typeDecNode = findTypeNode(type, methodInfo.fileIndex);
        if (typeDecNode.isPresent()) {
          graph.addEdge(methodDeclNode, typeDecNode.get(), new Edge(edgeCount++, EdgeType.RETURN));
        }
      }

      // param type
      for (String type : methodInfo.paramTypes) {
        Optional<Node> typeDecNode = findTypeNode(type, methodInfo.fileIndex);
        if (typeDecNode.isPresent()) {
          graph.addEdge(methodDeclNode, typeDecNode.get(), new Edge(edgeCount++, EdgeType.PARAM));
        }
      }

      // local var type
      for (String type : methodInfo.typeUses) {
        Optional<Node> typeDecNode = findTypeNode(type, methodInfo.fileIndex);
        if (typeDecNode.isPresent()) {
          graph.addEdge(
              methodDeclNode, typeDecNode.get(), new Edge(edgeCount++, EdgeType.INITIALIZE));
        }
      }
    }

    // 2. edges from field declaration
    for (FieldInfo fieldInfo : fieldDecMap.values()) {
      Node fieldDeclNode = fieldInfo.node;
      // field type
      for (String type : fieldInfo.types) {
        Optional<Node> typeDecNode = findTypeNode(type, fieldInfo.fileIndex);
        if (typeDecNode.isPresent()) {
          graph.addEdge(fieldDeclNode, typeDecNode.get(), new Edge(edgeCount++, EdgeType.TYPE));
        }
      }

      // method invocation
      for (IMethodBinding methodCall : fieldInfo.methodCalls) {
        MethodInfo targetMethodInfo = methodBindingMap.get(methodCall);
        if (targetMethodInfo != null) {
          graph.addEdge(fieldDeclNode, targetMethodInfo.node, new Edge(edgeCount++, EdgeType.CALL));
        }
      }

      // field access
      for (String fieldUse : fieldInfo.fieldUses) {
        FieldInfo targetFieldInfo = fieldDecMap.get(fieldUse);
        if (targetFieldInfo != null) {
          graph.addEdge(
              fieldDeclNode, targetFieldInfo.node, new Edge(edgeCount++, EdgeType.ACCESS));
        }
      }

      // type instance creation
      for (String type : fieldInfo.typeUses) {
        Optional<Node> typeDecNode = findTypeNode(type, fieldInfo.fileIndex);
        if (typeDecNode.isPresent()) {
          graph.addEdge(
              fieldDeclNode, typeDecNode.get(), new Edge(edgeCount++, EdgeType.INITIALIZE));
        }
      }
    }

    // 3. edges from hunk nodes
    for (HunkInfo hunkInfo : hunkMap.values()) {
      Node hunkNode = hunkInfo.node;
      // method invocation
      for (IMethodBinding methodCall : hunkInfo.methodCalls) {
        MethodInfo targetMethodInfo = methodBindingMap.get(methodCall);
        if (targetMethodInfo != null) {
          graph.addEdge(hunkNode, targetMethodInfo.node, new Edge(edgeCount++, EdgeType.CALL));
        }
      }

      // field access
      for (String fieldUse : hunkInfo.fieldUses) {
        FieldInfo targetFieldInfo = fieldDecMap.get(fieldUse);
        if (targetFieldInfo != null) {
          graph.addEdge(hunkNode, targetFieldInfo.node, new Edge(edgeCount++, EdgeType.ACCESS));
        }
      }

      // type uses
      for (String type : hunkInfo.typeUses) {
        Optional<Node> typeDecNode = findTypeNode(type, hunkInfo.fileIndex);
        if (typeDecNode.isPresent()) {
          graph.addEdge(hunkNode, typeDecNode.get(), new Edge(edgeCount++, EdgeType.INITIALIZE));
        }
      }
    }

    return graph;
  }

  /**
   * Find the type declaration node
   *
   * @param type
   * @return
   */
  private Optional<Node> findTypeNode(String type, Integer fileIndex) {
    // for qualified name
    if (entityPool.classInfoMap.containsKey(type)) {
      return Optional.of(entityPool.classInfoMap.get(type).node);
    } else if (entityPool.interfaceInfoMap.containsKey(type)) {
      return Optional.of(entityPool.interfaceInfoMap.get(type).node);
    } else if (entityPool.importInfoMap.containsKey(type)) {
      return Optional.of(entityPool.importInfoMap.get(type).node);
    }
    // for unqualified name: fuzzy matching in the imports of the current file
    for (Map.Entry<String, HunkInfo> entry : entityPool.importInfoMap.entrySet()) {
      if (entry.getValue().fileIndex == fileIndex && entry.getKey().endsWith(type)) {
        return Optional.of(entry.getValue().node);
      }
    }
    return Optional.empty();
  }
  /**
   * Collect info of the hunks in the current file
   *
   * @param hunksPosition
   * @param cu
   * @return
   */
  private void createHunkInfos(
      Integer fileIndex,
      Map<String, Pair<Integer, Integer>> hunksPosition,
      CompilationUnit cu,
      JDTService jdtService) {
    for (String index : hunksPosition.keySet()) {
      // for each diff hunk, find and analyze covered nodes, create hunk node and info
      Set<ASTNode> coveredNodes = new LinkedHashSet<>();
      int startPos = hunksPosition.get(index).getLeft();
      int length = hunksPosition.get(index).getRight();
      if (length > 0) {
        MyNodeFinder nodeFinder = new MyNodeFinder(cu, startPos, length);
        for (ASTNode node : nodeFinder.getCoveredNodes()) {
          while (node != null
              && !(node instanceof ImportDeclaration
                  || node instanceof Statement
                  || node instanceof BodyDeclaration)) {
            node = node.getParent();
          }
          coveredNodes.add(node);
        }
      }

      // if the hunk is empty, process the next hunk
      if (coveredNodes.isEmpty()) {
        continue;
      }

      HunkInfo hunkInfo = new HunkInfo(index);
      hunkInfo.fileIndex = fileIndex;
      hunkInfo.coveredNodes = coveredNodes;
      boolean existInGraph = false;

      // coveredNodes.isEmpty() --> added for BASE and deleted for CURRENT
      for (ASTNode astNode : coveredNodes) {
        if (astNode instanceof ImportDeclaration) {
          hunkInfo.typeDefs.add(((ImportDeclaration) astNode).getName().toString());
        } else if (astNode instanceof BodyDeclaration) {
          Optional<Node> nodeOpt = Optional.empty();
          // find the corresponding nodeOpt in the entity pool (expected to exist)
          switch (astNode.getNodeType()) {
            case ASTNode.TYPE_DECLARATION:
              ITypeBinding typeBinding = ((TypeDeclaration) astNode).resolveBinding();
              if (typeBinding != null) {
                nodeOpt =
                    findNodeByNameAndType(typeBinding.getQualifiedName(), NodeType.CLASS, true);
              } else {
                nodeOpt =
                    findNodeByNameAndType(
                        ((TypeDeclaration) astNode).getName().getIdentifier(),
                        NodeType.CLASS,
                        false);
              }

              if (nodeOpt.isPresent()) {
                existInGraph = true;
                Node node = nodeOpt.get();
                node.isInDiffHunk = true;
                node.diffHunkIndex = index;

                hunkInfo.typeDefs.add(node.getQualifiedName());
                hunkInfo.node = node;
              } else {
                logger.error("Not Found: " + astNode);
              }
              break;
            case ASTNode.FIELD_DECLARATION:
              List<VariableDeclarationFragment> fragments =
                  ((FieldDeclaration) astNode).fragments();
              for (VariableDeclarationFragment fragment : fragments) {
                IVariableBinding binding = fragment.resolveBinding();
                if (binding != null && binding.getDeclaringClass() != null) {
                  nodeOpt =
                      findNodeByNameAndType(
                          binding.getDeclaringClass().getQualifiedName() + ":" + binding.getName(),
                          NodeType.FIELD,
                          true);
                } else {
                  nodeOpt = findNodeByNameAndType(binding.getName(), NodeType.FIELD, false);
                }
                if (nodeOpt.isPresent()) {
                  existInGraph = true;
                  Node node = nodeOpt.get();
                  node.isInDiffHunk = true;
                  node.diffHunkIndex = index;

                  hunkInfo.fieldDefs.add(node.getQualifiedName());
                  hunkInfo.node = node;
                } else {
                  logger.error("Not Found: " + astNode);
                }
              }
              break;
            case ASTNode.METHOD_DECLARATION:
              MethodDeclaration methodDeclaration = (MethodDeclaration) astNode;
              String uniqueMethodName = methodDeclaration.getName().getIdentifier();
              IMethodBinding methodBinding = methodDeclaration.resolveBinding();
              if (methodBinding != null && methodBinding.getDeclaringClass() != null) {
                // get the unique name of the method, including the parameter string
                uniqueMethodName =
                    jdtService.getUniqueNameForMethod(
                        methodBinding.getDeclaringClass().getQualifiedName(), methodDeclaration);
                nodeOpt = findNodeByNameAndType(uniqueMethodName, NodeType.METHOD, true);
              } else {
                nodeOpt = findNodeByNameAndType(uniqueMethodName, NodeType.METHOD, false);
              }

              if (nodeOpt.isPresent()) {
                existInGraph = true;
                Node node = nodeOpt.get();
                node.isInDiffHunk = true;
                node.diffHunkIndex = index;

                hunkInfo.methodDefs.add(node.getQualifiedName());
                hunkInfo.node = node;
              } else {
                logger.error("Not Found: " + astNode);
              }
              break;
            default:
              logger.error("Other type: " + astNode.getNodeType());
          }
        } else if (astNode instanceof Statement) {
          jdtService.parseStatement(hunkInfo, (Statement) astNode);
        }
      }
      // create the HunkInfo node for hunks inside entities
      if (!existInGraph) {
        int nodeID = graph.vertexSet().size() + 1;
        int edgeID = graph.edgeSet().size() + 1;
        Node hunkNode =
            new Node(nodeID, NodeType.HUNK, hunkInfo.uniqueName(), hunkInfo.uniqueName());
        hunkNode.isInDiffHunk = true;
        hunkNode.diffHunkIndex = index;

        hunkInfo.node = hunkNode;
        graph.addVertex(hunkNode);
        // find parent entity node (expected to exist) and create the contain edge
        Optional<Node> parentNodeOpt = findParentNode(coveredNodes);
        if (parentNodeOpt.isPresent()) {
          graph.addEdge(parentNodeOpt.get(), hunkNode, new Edge(edgeID, EdgeType.CONTAIN));
        }
      }

      // for import declarations, map imported type to HunkInfo
      if (hunkInfo.typeDefs.size() > 0) {
        for (String s : hunkInfo.typeDefs) {
          entityPool.importInfoMap.put(s, hunkInfo);
        }
      }
      // add HunkInfo into the pool
      entityPool.hunkInfoMap.put(hunkInfo.uniqueName(), hunkInfo);
    }
  }

  /**
   * Compute and construct a map to store the position of diff hunks inside current file
   *
   * @param diffFile
   * @param cu
   * @return
   */
  private Map<String, Pair<Integer, Integer>> computeHunksPosition(
      DiffFile diffFile, CompilationUnit cu, Version version) {
    Map<String, Pair<Integer, Integer>> indexToPositionMap = new HashMap<>();
    if (cu != null) {

      List<DiffHunk> diffHunksContainCode =
          diffFile.getDiffHunks().stream()
              .filter(diffHunk -> diffHunk.containsCode())
              .collect(Collectors.toList());
      for (DiffHunk diffHunk : diffHunksContainCode) {
        // compute the pos of all diff hunks that contains code
        int startPos = -1;
        int endPos = -1;
        switch (version) {
          case BASE:
            startPos = cu.getPosition(diffHunk.getBaseStartLine(), 0);
            endPos =
                cu.getPosition(
                    diffHunk.getBaseEndLine(), diffHunk.getBaseHunk().getLastLineLength());
            break;
          case CURRENT:
            startPos = cu.getPosition(diffHunk.getCurrentStartLine(), 0);
            endPos =
                cu.getPosition(
                    diffHunk.getCurrentEndLine(), diffHunk.getCurrentHunk().getLastLineLength());
        }
        int length = endPos - startPos;
        // construct the location map
        indexToPositionMap.put(
            diffFile.getIndex().toString() + ":" + diffHunk.getIndex().toString(),
            Pair.of(startPos, length));
      }
    }
    return indexToPositionMap;
  }

  /**
   * Find the nearest common ancestor entity in the ast and the node in the graph
   *
   * @param astNodes
   * @return
   */
  private Optional<Node> findParentNode(Set<ASTNode> astNodes) {
    // TODO: find the nearest common ancestor of the covered ast nodes
    ASTNode parentEntity = null;
    for (ASTNode astNode : astNodes) {
      while (astNode != null && !(astNode instanceof BodyDeclaration)) {
        astNode = astNode.getParent();
      }
      parentEntity = astNode;
    }
    if (parentEntity != null) {
      String identifier = "";
      switch (parentEntity.getNodeType()) {
        case ASTNode.TYPE_DECLARATION:
          identifier = ((TypeDeclaration) parentEntity).getName().getFullyQualifiedName();
          break;
        case ASTNode.FIELD_DECLARATION:
          List<VariableDeclarationFragment> fragments =
              ((FieldDeclaration) parentEntity).fragments();
          identifier = fragments.get(0).getName().getFullyQualifiedName();
          break;
        case ASTNode.METHOD_DECLARATION:
          identifier = ((MethodDeclaration) parentEntity).getName().getFullyQualifiedName();
          break;
      }
      String finalIdentifier = identifier;
      return graph.vertexSet().stream()
          .filter(node -> node.getIdentifier().equals(finalIdentifier))
          .findAny();
    }
    return Optional.empty();
  }

  /**
   * Find the corresponding node in graph by name (qualified name first, simple name if no qualified
   * name) and type
   *
   * @param name
   * @param type
   * @return
   */
  private Optional<Node> findNodeByNameAndType(
      String name, NodeType type, Boolean isQualifiedName) {
    if (isQualifiedName) {
      return graph.vertexSet().stream()
          .filter(node -> node.getType().equals(type) && node.getQualifiedName().equals(name))
          .findAny();
    } else {
      return graph.vertexSet().stream()
          .filter(node -> node.getType().equals(type) && node.getIdentifier().equals(name))
          .findAny();
    }
  }

  /**
   * Given the absolute path, return the corresponding diff file
   *
   * @param absolutePath
   * @return
   */
  private Optional<DiffFile> getDiffFileByPath(String absolutePath, Version version) {
    String formattedPath = Utils.formatPath(absolutePath);
    switch (version) {
      case BASE:
        return this.diffFiles.stream()
            .filter(diffFile -> formattedPath.endsWith(diffFile.getBaseRelativePath()))
            .findAny();
      case CURRENT:
        return this.diffFiles.stream()
            .filter(diffFile -> formattedPath.endsWith(diffFile.getCurrentRelativePath()))
            .findAny();
      default:
        return Optional.empty();
    }
  }
}
