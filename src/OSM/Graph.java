package OSM;

/**
 * Derived from code at http://stackoverflow.com/a/2739768/166920
 */
import java.util.*;

public class Graph {

    static class Node{
        public final long osm_id;
        public final HashSet<Edge> inEdges;
        public final HashSet<Edge> outEdges;
        public Node(long osm_id) {
            this.osm_id = osm_id;
            inEdges = new HashSet<Edge>();
            outEdges = new HashSet<Edge>();
        }
        public Node addEdge(Node node){
            Edge e = new Edge(this, node);
            outEdges.add(e);
            node.inEdges.add(e);
            return this;
        }
        @Override
        public String toString() {
            return Long.toString(osm_id);
        }
    }

    static class Edge{
        public final Node from;
        public final Node to;
        public Edge(Node from, Node to) {
            this.from = from;
            this.to = to;
        }
        @Override
        public boolean equals(Object obj) {
            Edge e = (Edge)obj;
            return e.from == from && e.to == to;
        }
    }

    /**
     * Sorts the given relations so that relations with other relations as members are always placed
     * after those contained members.  Important for generating valid OSM XML files.
     * @param allRelations
     * @return
     */
    protected static ArrayList<OSMRelation> sortRelationsTopologically(final HashMap<Long, OSMRelation> allRelations) {
        final HashMap<Long, Node> relationNodes = new HashMap<>(allRelations.size());
        for(final OSMRelation relation : allRelations.values()) {
            Node node = relationNodes.get(relation.osm_id);
            if(node == null) {
                node = new Node(relation.osm_id);
                relationNodes.put(relation.osm_id, node);
            }
            for(final OSMRelation.OSMRelationMember member : relation.getMembers()) {
                if(member.member instanceof OSMRelation) {
                    Node memberNode = relationNodes.get(member.member.osm_id);
                    if(memberNode == null) {
                        memberNode = new Node(member.member.osm_id);
                        relationNodes.put(member.member.osm_id, memberNode);
                    }
                    node.addEdge(memberNode);
                }
            }
        }

        //L <- Empty list that will contain the sorted elements
        ArrayList<Node> L = new ArrayList<>(relationNodes.size());

        //S <- Set of all nodes with no incoming edges
        HashSet<Node> S = new HashSet<Node>();
        for(Node n : relationNodes.values()){
            if(n.inEdges.size() == 0){
                S.add(n);
            }
        }

        //while S is non-empty do
        while(!S.isEmpty()){
            //remove a node n from S
            Node n = S.iterator().next();
            S.remove(n);

            //insert n into L
            L.add(n);

            //for each node m with an edge e from n to m do
            for(Iterator<Graph.Edge> it = n.outEdges.iterator();it.hasNext();){
                //remove edge e from the graph
                Graph.Edge e = it.next();
                Node m = e.to;
                it.remove();//Remove edge from n
                m.inEdges.remove(e);//Remove edge from m

                //if m has no other incoming edges then insert m into S
                if(m.inEdges.isEmpty()){
                    S.add(m);
                }
            }
        }
        //Check to see if all edges are removed
        boolean cycle = false;
        for(Node n : relationNodes.values()){
            if(!n.inEdges.isEmpty()){
                cycle = true;
                break;
            }
        }

        //Cycle present, topological sort not possible
        if(cycle){
            return null;
        }

        //otherwise, create an ArrayList of the sorted relations
        final ArrayList<OSMRelation> sortedRelations = new ArrayList<>(relationNodes.size());
        for(final Node node : L) {
            sortedRelations.add(allRelations.get(node.osm_id));
        }

        //relations with the most
        Collections.reverse(sortedRelations);
        return sortedRelations;
    }
}
