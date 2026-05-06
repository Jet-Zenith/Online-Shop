@echo off
echo Testing Redis Online Shop API...
echo.

echo 1. Testing Get All Products
curl -X GET http://localhost:8080/api/products
echo.
echo.

echo 2. Testing User Registration
curl -X POST http://localhost:8080/api/users/register ^
  -H "Content-Type: application/json" ^
  -d '{"username":"test_user","email":"test@example.com","password":"password123"}'
echo.
echo.

echo 3. Testing User Login
curl -X POST http://localhost:8080/api/users/login ^
  -H "Content-Type: application/json" ^
  -d '{"username":"test_user","password":"password123"}'
echo.
echo.

echo 4. Testing Get Hot Products
curl -X GET http://localhost:8080/api/products/hot
echo.
echo.

echo 5. Testing Search Products
curl -X GET "http://localhost:8080/api/products/search?keyword=iphone"
echo.
echo.

echo API Testing completed!
pause