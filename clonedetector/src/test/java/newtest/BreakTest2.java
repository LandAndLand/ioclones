package newtest;

public class BreakTest2 extends BreakTest1 implements Runnable {

	public static void main(String[] args) {
		BreakTest1 test = new BreakTest2();
		test.createPerson1();
		test.createPerson2();
	}

	@Override
	public void run() {
		BreakTest1 test = new BreakTest2();
		test.createPerson1();
		test.createPerson2();
	}

}
