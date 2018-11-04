package nodes4j.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BinaryOperator;

import org.apache.commons.lang3.mutable.MutableObject;

import actor4j.core.actors.Actor;
import actor4j.core.actors.ActorDistributedGroupMember;
import actor4j.core.immutable.ImmutableList;
import actor4j.core.messages.ActorMessage;
import actor4j.core.utils.ActorGroup;
import actor4j.core.utils.ActorGroupList;

import static actor4j.core.utils.CommPattern.*;
import static nodes4j.core.ActorMessageTag.*;

public class TaskActor<T, R> extends Actor implements ActorDistributedGroupMember {
	protected NodeOperations<T, R> operations;
	protected BinaryOperator<List<R>> defaultAccumulator;
	protected ActorGroupList group;
	protected ActorGroup hubGroup;
	protected ActorMessageTag dest_tag;
	
	protected MutableObject<Object> result;
	protected int level;
	
	public TaskActor(String name, NodeOperations<T, R> operations, ActorGroupList group, ActorGroup hubGroup, ActorMessageTag dest_tag) {
		super(name);
		
		this.operations = operations;
		defaultAccumulator = new BinaryOperator<List<R>>() {
			@Override
			public List<R> apply(List<R> left, List<R> right) {
				List<R> result = new ArrayList<>(left.size()+right.size());
				result.addAll(left);
				result.addAll(right);		
				return result;
			}
		};
		this.group = group;
		this.hubGroup = hubGroup;
		this.dest_tag = dest_tag;
		
		result = new MutableObject<>();
	}

	@SuppressWarnings("unchecked")
	protected void treeReduction(ActorMessage<?> message) {
		int grank = group.indexOf(self());
		if (grank%(1<<(level+1))>0) { 
			int dest = grank-(1<<level);
			//System.out.printf("[level: %d] rank %d has sended a message (%s) to rank %d%n", level, group.indexOf(getSelf()), result.getValue().toString(), dest);
			send(new ActorMessage<>(new ImmutableList<R>((List<R>)result.getValue()), REDUCE, self(), group.get(dest)));
			stop();
		}
		else if (message.tag==REDUCE.ordinal() && message.value!=null && message.value instanceof ImmutableList){
			List<R> buf = ((ImmutableList<R>)message.value).get();
			//System.out.printf("[level: %d] rank %d has received a message (%s) from rank %d%n", level, group.indexOf(getSelf()), buf.toString(), group.indexOf(getSender()));
			if (operations.accumulator!=null)
				result.setValue(operations.accumulator.apply((List<R>)result.getValue(), buf));
			else
				result.setValue(    defaultAccumulator.apply((List<R>)result.getValue(), buf));
			
			level++;
			message.tag = TASK.ordinal();
			treeReduction(message);
		}
		else {
			int source = grank+(1<<level);
			if (source>group.size()-1)
				if (grank==0) {
					broadcast(new ActorMessage<>(new ImmutableList<R>((List<R>)result.getValue()), dest_tag, self(), null), this, hubGroup);
					stop();
					return;
				} else {
					level++;
					treeReduction(message);
				}
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public void receive(ActorMessage<?> message) {
		if (message.tag==TASK.ordinal() && message.value!=null && message.value instanceof ImmutableList) {
			ImmutableList<T> immutableList = (ImmutableList<T>)message.value;
			
			if (operations.mapAsList!=null)
				result.setValue(operations.mapAsList.apply(immutableList.get()));
			else {
				List<R> list = new ArrayList<>(immutableList.get().size());
				for (T t : immutableList.get()) {
					if (operations.filter!=null)
						if (!operations.filter.test(t))
							continue;
					if (operations.mapper!=null)
						list.add(operations.mapper.apply(t));
					else
						list.add((R)t);
					if (operations.action!=null)
						operations.action.accept(t);	
				}
				result.setValue(list);
			}

			level = 0;
		} 
		
		if (message.tag==TASK.ordinal() || message.tag==REDUCE.ordinal())
			treeReduction(message);
	}

	@Override
	public UUID getGroupId() {
		return group.getId();
	}
}