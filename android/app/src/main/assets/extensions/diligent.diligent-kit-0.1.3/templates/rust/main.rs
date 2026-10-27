// {{label}}: a Rust program, built and run with cargo.

fn main() {
    println!("Hello from {{label}} in Rust");
    let squares: Vec<u32> = (1..=5).map(|n| n * n).collect();
    println!("Squares: {:?}", squares);
}
